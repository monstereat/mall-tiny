package com.macro.mall.tiny.modules.monitor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorReplay;
import com.macro.mall.tiny.modules.monitor.repository.ClickHouseEventRepository;
import com.macro.mall.tiny.modules.monitor.service.*;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后台事件处理入口：Kafka 把已接收的事件交给这里；本类检查重复事件和项目状态，再写入 ClickHouse。
 * 如果事件是错误，还会更新 MySQL 中的问题汇总；满足规则的事件也会进入告警判断。
 */
@Component
@RequiredArgsConstructor
public class MonitorEventConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorEventConsumer.class);

    private static final Pattern TRACEPARENT = Pattern.compile(
            "(?i)^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(?:-([0-9a-f]+))?$"
    );

    /** 暂存已解析、已检查项目并算好错误指纹的事件，避免后续步骤重复查库或重复计算。 */
    private record PreparedEvent(
            int recordIndex,
            MonitorEventEnvelope event,
            MonitorProject project,
            String fingerprint
    ) {
    }

    /** 记录事件编号以及它在原始 Kafka 批次中的位置，失败时才能只释放待重试部分。 */
    private record ReservedEvent(int recordIndex, String eventId) {
    }

    private final ObjectMapper objectMapper;
    private final ClickHouseEventRepository repository;
    private final ErrorFingerprintService fingerprintService;
    private final MonitorProjectService projectService;
    private final MonitorIssueService issueService;
    private final MonitorEventDeduplicator deduplicator;
    private final MonitorReplayService replayService;
    private final MonitorAlertEngine alertEngine;
    private final Tracer tracer;

    @KafkaListener(topics = "${monitor.kafka.topics.error}")
    public void consumeError(List<ConsumerRecord<String, String>> records) {
        persistBatch(records, MonitorEventType.ERROR);
    }

    @KafkaListener(topics = "${monitor.kafka.topics.performance}")
    public void consumePerformance(List<ConsumerRecord<String, String>> records) {
        persistBatch(records, MonitorEventType.PERFORMANCE);
    }

    @KafkaListener(topics = "${monitor.kafka.topics.behavior}")
    public void consumeBehavior(List<ConsumerRecord<String, String>> records) {
        persistBatch(records, MonitorEventType.BEHAVIOR);
    }

    @KafkaListener(topics = "${monitor.kafka.topics.replay}")
    public void consumeReplay(List<ConsumerRecord<String, String>> records) {
        persistBatch(records, MonitorEventType.REPLAY);
    }

    @KafkaListener(topics = "${monitor.kafka.topics.metric}")
    public void consumeMetric(List<ConsumerRecord<String, String>> records) {
        persistBatch(records, MonitorEventType.METRIC);
    }

    @KafkaListener(topics = "${monitor.kafka.topics.profile}")
    public void consumeProfile(List<ConsumerRecord<String, String>> records) {
        persistBatch(records, MonitorEventType.PROFILE);
    }

    @KafkaListener(topics = "${monitor.kafka.topics.span:monitor-span-v1}")
    public void consumeSpan(List<ConsumerRecord<String, String>> records) {
        persistBatch(records, MonitorEventType.SPAN);
    }

    private void persistBatch(List<ConsumerRecord<String, String>> records, MonitorEventType expectedType) {
        if (records == null || records.isEmpty()) {
            return;
        }

        // 一批事件可能来自不同的请求链路：用第一条作为主链路，其余记录为关联链路，方便追踪批次处理。
        List<TraceContext> parents = records.stream()
                .map(record -> traceContext(record.headers().lastHeader("traceparent")))
                .filter(context -> context != null)
                .toList();
        TraceContext primaryParent = parents.stream()
                .filter(context -> Boolean.TRUE.equals(context.sampled()))
                .findFirst()
                .orElseGet(() -> parents.stream().findFirst().orElse(null));
        // cd： 记录处理那个topic，消息多少条
        Span.Builder spanBuilder = tracer.spanBuilder()
                .name("monitor.kafka.consume")
                .kind(Span.Kind.CONSUMER)
                .tag("messaging.system", "kafka")
                .tag("messaging.operation", "process")
                .tag("messaging.destination.name", records.get(0).topic())
                .tag("messaging.batch.message_count", records.size());
        if (primaryParent == null) {
            // cd： 没有主链路，直接设置为根链路
            spanBuilder.setNoParent();
        } else {
            spanBuilder.setParent(primaryParent);
            // cd： 其他链路记录为关联链路
            for (TraceContext parent : parents) {
                if (!parent.traceId().equals(primaryParent.traceId())
                        || !parent.spanId().equals(primaryParent.spanId())) {
                    spanBuilder.addLink(new Link(parent));
                }
            }
        }
        Span batchSpan = spanBuilder.start();
        // cd： 开始处理批次事件（监控批量消息处理）

        // cd： 批次追踪设为当前上下文
        // try-with-resources 会在代码块结束时自动恢复之前的链路上下文，让本批日志和后续子步骤带上批次编号。
        try (Tracer.SpanInScope ignored = tracer.withSpan(batchSpan)) {
            // cd：取出卡夫卡消息交给persistplayloads处理
            persistPayloads(records, expectedType);
        } catch (RuntimeException ex) {
            batchSpan.error(ex);
            throw ex;
        } finally {
            batchSpan.end();
        }
    }

    private void persistPayloads(List<ConsumerRecord<String, String>> records, MonitorEventType expectedType) {
        List<PreparedEvent> prepared = new ArrayList<>();
        // 记录本批拿到处理资格的事件编号；如果中途失败，就释放这些编号，让失败事件可以重试。
        List<ReservedEvent> reservedEvents = new ArrayList<>();
        BatchListenerFailedException preparationFailure = null;

        try {
            for (int index = 0; index < records.size(); index++) {
                try {
                    ConsumerRecord<String, String> record = records.get(index);
                    // Kafka 里保存的是 JSON 字符串；先还原成 Java 对象，后续代码就可以按字段处理。
                    MonitorEventEnvelope event = parse(record.value());
                    // 正常主题和事件类型要一一对应；放错主题的数据不能继续写入错误的数据表。
                    if (event.getEventType() != expectedType) {
                        throw new IllegalArgumentException("event type does not match topic: " + expectedType);
                    }
                    // 先在 Redis 登记“这个编号正在处理”；重复投递会跳过，失败时会释放待重试部分。
                    if (!deduplicator.reserve(event.getEventId())) {
                        continue;
                    }
                    reservedEvents.add(new ReservedEvent(index, event.getEventId()));

                    // 查询事件所属项目，并确认项目仍处于启用状态。
                    MonitorProject project = projectService.getActiveProject(event.getProjectId());
                    if (project == null) {
                        throw new IllegalArgumentException(
                                "monitor project is disabled or missing: " + event.getProjectId());
                    }
                    // 错误事件需要生成指纹来归并成 Issue；其他事件不参与错误归并。
                    String fingerprint = expectedType == MonitorEventType.ERROR
                            ? fingerprintService.generate(event)
                            : "";
                    // Replay 原始内容可能很大，写对象存储后，事件表里只留下可查询的小型引用。
                    if (expectedType == MonitorEventType.REPLAY) {
                        Map<String, Object> replayRef = new LinkedHashMap<>();
                        try {
                            // 回放片段很大，实际内容放对象存储；事件表只保存回放编号等引用信息。
                            MonitorReplay replay = replayService.store(project, event);
                            replayRef.put("replayId", replay.getId());
                            replayRef.put("eventCount", replay.getEventCount());
                            replayRef.put("sessionId", replay.getSessionId());
                            replayRef.put("format", "rrweb");
                        } catch (MonitorReplayQuotaExceededException e) {
                            // 项目回放空间用完时，不上传大文件；仍保留事件，并标记原因供页面说明。
                            LOGGER.warn("Skipping Replay upload after project quota was exceeded: {}", e.getMessage());
                            replayRef.put("quotaExceeded", true);
                        }
                        event.setData(replayRef);
                    }

                    prepared.add(new PreparedEvent(index, event, project, fingerprint));
                } catch (RuntimeException ex) {
                    // 先停止准备后面的记录；只有这个位置前的数据会被写好，Kafka 才能安全提交它们的偏移量。
                    preparationFailure = failedRecord(ex, index, "failed to prepare monitor event");
                    break;
                }
            }

            // 先把原始事件批量写入，再更新问题和告警汇总；后续失败时，重试会依靠事件编号避免重复累计。
            repository.saveBatch(
                // cd： 那数据两个字段，批量保存
                    prepared.stream()
                            .map(item -> new ClickHouseEventRepository.StoredEvent(
                                    item.event(),
                                    item.fingerprint()
                            ))
                            .toList()
            );

            for (PreparedEvent item : prepared) {
                try {
                    // 错误事件会更新 Issue 和告警；其他支持告警的事件只走告警判断。
                    if (expectedType == MonitorEventType.ERROR) {
                        issueService.aggregate(item.project(), item.event(), item.fingerprint());
                        alertEngine.evaluate(item.project(), item.event(), item.fingerprint());
                    } else if (expectedType == MonitorEventType.PERFORMANCE
                            || expectedType == MonitorEventType.METRIC
                            || expectedType == MonitorEventType.BEHAVIOR) {
                        alertEngine.evaluate(item.project(), item.event(), "");
                    }
                } catch (RuntimeException ex) {
                    // 前面的事件已完成汇总；将失败事件的位置交给 Spring Kafka，避免整批都进死信。
                    throw failedRecord(ex, item.recordIndex(), "failed to aggregate monitor event");
                }
            }

            if (preparationFailure != null) {
                // 记录前缀已处理完，现在可以安全告诉 Kafka 从这条具体记录开始重试或进入死信。
                throw preparationFailure;
            }
        } catch (RuntimeException ex) {
            // 指定了失败位置时，只释放这条及之后会被 Kafka 重投的编号；失败位置之前的数据不再重投。
            // 整批写入异常没有具体位置，就释放整批占位并按原策略整批重试。
            int retryFromIndex = ex instanceof BatchListenerFailedException batchFailure
                    ? batchFailure.getIndex()
                    : 0;
            reservedEvents.stream()
                    .filter(reserved -> reserved.recordIndex() >= retryFromIndex)
                    .forEach(reserved -> deduplicator.release(reserved.eventId()));
            // 必须继续抛出：Kafka 才会执行配置好的重试或死信恢复。
            throw ex;
        }
    }

    private BatchListenerFailedException failedRecord(RuntimeException cause, int index, String message) {
        return new BatchListenerFailedException(message, cause, index);
    }

    private TraceContext traceContext(Header header) {
        if (header == null) {
            return null;
        }

        Matcher matcher = TRACEPARENT.matcher(new String(header.value(), StandardCharsets.UTF_8));
        if (!matcher.matches()) {
            return null;
        }

        String version = matcher.group(1);
        String traceId = matcher.group(2);
        String spanId = matcher.group(3);
        if ("ff".equalsIgnoreCase(version)
                || traceId.matches("0{32}")
                || spanId.matches("0{16}")) {
            return null;
        }

        boolean sampled = (Integer.parseInt(matcher.group(4), 16) & 1) == 1;
        return tracer.traceContextBuilder()
                .traceId(traceId)
                .spanId(spanId)
                .sampled(sampled)
                .build();
    }

    private MonitorEventEnvelope parse(String payload) {
        try {
            return objectMapper.readValue(payload, MonitorEventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid monitor event payload", e);
        }
    }
}
