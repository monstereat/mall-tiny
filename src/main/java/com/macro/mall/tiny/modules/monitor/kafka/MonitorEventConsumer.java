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
            MonitorEventEnvelope event,
            MonitorProject project,
            String fingerprint
    ) {
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

        Span.Builder spanBuilder = tracer.spanBuilder()
                .name("monitor.kafka.consume")
                .kind(Span.Kind.CONSUMER)
                .tag("messaging.system", "kafka")
                .tag("messaging.operation", "process")
                .tag("messaging.destination.name", records.get(0).topic())
                .tag("messaging.batch.message_count", records.size());
        if (primaryParent == null) {
            spanBuilder.setNoParent();
        } else {
            spanBuilder.setParent(primaryParent);
            for (TraceContext parent : parents) {
                if (!parent.traceId().equals(primaryParent.traceId())
                        || !parent.spanId().equals(primaryParent.spanId())) {
                    spanBuilder.addLink(new Link(parent));
                }
            }
        }
        Span batchSpan = spanBuilder.start();

        // try-with-resources 会在代码块结束时自动恢复之前的链路上下文，让本批日志和后续子步骤带上批次编号。
        try (Tracer.SpanInScope ignored = tracer.withSpan(batchSpan)) {
            persistPayloads(records.stream().map(ConsumerRecord::value).toList(), expectedType);
        } catch (RuntimeException ex) {
            batchSpan.error(ex);
            throw ex;
        } finally {
            batchSpan.end();
        }
    }

    private void persistPayloads(List<String> payloads, MonitorEventType expectedType) {
        List<PreparedEvent> prepared = new ArrayList<>();
        // 记录本批拿到处理资格的事件编号；如果中途失败，就释放这些编号，让失败事件可以重试。
        List<String> reservedEventIds = new ArrayList<>();

        try {
            for (String payload : payloads) {
                MonitorEventEnvelope event = parse(payload);
                if (event.getEventType() != expectedType) {
                    throw new IllegalArgumentException("event type does not match topic: " + expectedType);
                }
                // 先在 Redis 登记“这个编号正在处理”；重复消息会跳过，失败时下方会删掉登记以允许重试。
                if (!deduplicator.reserve(event.getEventId())) {
                    continue;
                }
                reservedEventIds.add(event.getEventId());

                MonitorProject project = projectService.getActiveProject(event.getProjectId());
                if (project == null) {
                    throw new IllegalArgumentException("monitor project is disabled or missing: " + event.getProjectId());
                }

                String fingerprint = expectedType == MonitorEventType.ERROR
                        ? fingerprintService.generate(event)
                        : "";

                if (expectedType == MonitorEventType.REPLAY) {
                    Map<String, Object> replayRef = new LinkedHashMap<>();
                    try {
                        // 回放原始片段较大，放对象存储；ClickHouse 只记回放编号等小型引用信息。
                        MonitorReplay replay = replayService.store(project, event);
                        replayRef.put("replayId", replay.getId());
                        replayRef.put("eventCount", replay.getEventCount());
                        replayRef.put("sessionId", replay.getSessionId());
                        replayRef.put("format", "rrweb");
                    } catch (MonitorReplayQuotaExceededException e) {
                        // 超出项目回放空间上限时只跳过大文件上传；事件仍入库，并标记原因供页面说明。
                        LOGGER.warn("Skipping Replay upload after project quota was exceeded: {}", e.getMessage());
                        replayRef.put("quotaExceeded", true);
                    }
                    event.setData(replayRef);
                }

                prepared.add(new PreparedEvent(event, project, fingerprint));
            }

            // 先把原始事件批量写入，再更新问题和告警汇总；后续失败时，重试会依靠事件编号避免重复累计。
            repository.saveBatch(
                    prepared.stream()
                            .map(item -> new ClickHouseEventRepository.StoredEvent(
                                    item.event(),
                                    item.fingerprint()
                            ))
                            .toList()
            );

            for (PreparedEvent item : prepared) {
                if (expectedType == MonitorEventType.ERROR) {
                    issueService.aggregate(item.project(), item.event(), item.fingerprint());
                    alertEngine.evaluate(item.project(), item.event(), item.fingerprint());
                } else if (expectedType == MonitorEventType.PERFORMANCE
                        || expectedType == MonitorEventType.METRIC
                        || expectedType == MonitorEventType.BEHAVIOR) {
                    alertEngine.evaluate(item.project(), item.event(), "");
                }
            }
        } catch (RuntimeException ex) {
            // At-least-once delivery: release batch reservations and allow Kafka retry/DLQ.
            // ClickHouse uses ReplacingMergeTree(event_id) so raw-event retries are dedupe-friendly.
            // 必须继续抛出异常：Kafka 才知道本批失败，并按配置重试或放入死信队列，而不会误认为处理成功。
            reservedEventIds.forEach(deduplicator::release);
            throw ex;
        }
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
