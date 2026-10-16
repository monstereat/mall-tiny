package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDeadLetterRecordView;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDeadLetterReplayRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDeadLetterSummaryView;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 按 Kafka 的主题、分区和偏移量读取一条死信，并提供受权限保护的人工重放。
 * 重放只把原始消息重新发回原主题，不会修改死信主题里的原记录。
 */
@Service
public class MonitorDeadLetterService {

    private static final Duration KAFKA_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(250);
    private static final Duration LIST_SCAN_TIMEOUT = Duration.ofSeconds(10);
    private static final long RECORD_LOOKUP_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long SEND_TIMEOUT_SECONDS = 10;
    private static final Duration REPLAY_IN_PROGRESS_TTL = Duration.ofMinutes(2);
    private static final Duration REPLAY_COMPLETED_TTL = Duration.ofDays(7);
    private static final int FAILURE_MESSAGE_LIMIT = 1000;
    private static final int MAX_LIST_LIMIT = 100;
    private static final long MAX_LIST_OFFSETS_PER_PARTITION = 5000;

    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, MonitorEventType> sourceTopics;

    public MonitorDeadLetterService(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${monitor.kafka.topics.error}") String errorTopic,
            @Value("${monitor.kafka.topics.performance}") String performanceTopic,
            @Value("${monitor.kafka.topics.behavior}") String behaviorTopic,
            @Value("${monitor.kafka.topics.replay}") String replayTopic,
            @Value("${monitor.kafka.topics.metric}") String metricTopic,
            @Value("${monitor.kafka.topics.profile}") String profileTopic,
            @Value("${monitor.kafka.topics.span:monitor-span-v1}") String spanTopic) {
        this.consumerFactory = consumerFactory;
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.sourceTopics = Map.of(
                errorTopic, MonitorEventType.ERROR,
                performanceTopic, MonitorEventType.PERFORMANCE,
                behaviorTopic, MonitorEventType.BEHAVIOR,
                replayTopic, MonitorEventType.REPLAY,
                metricTopic, MonitorEventType.METRIC,
                profileTopic, MonitorEventType.PROFILE,
                spanTopic, MonitorEventType.SPAN
        );
    }

    /**
     * 查询某个项目最近的死信摘要。这里扫描每个分区尾部有限范围，避免后台列表一次读完整个 Kafka 主题。
     * 原始消息内容只在用户点开单条记录时读取，列表里不返回 payload。
     */
    public List<MonitorDeadLetterSummaryView> list(
            MonitorProject project, String sourceTopic, int limit) {
        MonitorEventType expectedType = sourceTopics.get(sourceTopic);
        if (expectedType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported monitor source topic");
        }
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + MAX_LIST_LIMIT);
        }

        String deadLetterTopic = sourceTopic + ".dlq";
        try (Consumer<String, String> consumer = consumerFactory.createConsumer(
                null,
                "monitor-dlq-admin-list",
                UUID.randomUUID().toString(),
                consumerOverrides())) {
            List<PartitionInfo> partitionInfo = consumer.partitionsFor(deadLetterTopic, KAFKA_REQUEST_TIMEOUT);
            if (partitionInfo == null || partitionInfo.isEmpty()) {
                return List.of();
            }

            List<TopicPartition> allPartitions = partitionInfo.stream()
                    .map(info -> new TopicPartition(deadLetterTopic, info.partition()))
                    .toList();
            Map<TopicPartition, Long> beginningOffsets =
                    consumer.beginningOffsets(allPartitions, KAFKA_REQUEST_TIMEOUT);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(allPartitions, KAFKA_REQUEST_TIMEOUT);
            List<TopicPartition> partitionsToScan = new ArrayList<>();
            Map<TopicPartition, Long> scanEndOffsets = new HashMap<>();
            Map<TopicPartition, Long> scanStartOffsets = new HashMap<>();
            for (TopicPartition partition : allPartitions) {
                long beginning = beginningOffsets.getOrDefault(partition, 0L);
                long end = endOffsets.getOrDefault(partition, beginning);
                // 只从分区末尾向前看最多 5000 个 offset；已经被保留策略清理的 offset 不会再被扫描。
                long start = Math.max(beginning, end - MAX_LIST_OFFSETS_PER_PARTITION);
                if (start < end) {
                    partitionsToScan.add(partition);
                    scanStartOffsets.put(partition, start);
                    scanEndOffsets.put(partition, end);
                }
            }
            if (partitionsToScan.isEmpty()) {
                return List.of();
            }

            // 使用手动 assign/seek 定位，不加入业务消费者组，也不会改变正常消费进度。
            consumer.assign(partitionsToScan);
            partitionsToScan.forEach(partition -> consumer.seek(partition, scanStartOffsets.get(partition)));

            List<MonitorDeadLetterSummaryView> summaries = new ArrayList<>();
            Set<TopicPartition> completedPartitions = new HashSet<>();
            long deadline = System.nanoTime() + LIST_SCAN_TIMEOUT.toNanos();
            while (completedPartitions.size() < partitionsToScan.size() && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                for (TopicPartition partition : partitionsToScan) {
                    long snapshotEnd = scanEndOffsets.get(partition);
                    for (ConsumerRecord<String, String> record : records.records(partition)) {
                        // end offset 是本次查询开始时的快照；并发新写入的记录留给下一次刷新。
                        if (record.offset() >= snapshotEnd) {
                            continue;
                        }
                        MonitorDeadLetterSummaryView summary = toSummary(project, sourceTopic, expectedType, record);
                        if (summary != null) {
                            summaries.add(summary);
                        }
                    }
                    if (consumer.position(partition) >= snapshotEnd) {
                        completedPartitions.add(partition);
                    }
                }
            }
            if (completedPartitions.size() < partitionsToScan.size()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "timed out while listing Kafka dead-letter records");
            }

            summaries.sort(Comparator
                    .comparingLong(MonitorDeadLetterSummaryView::deadLetterTimestamp).reversed()
                    .thenComparing(Comparator.comparingInt(
                            MonitorDeadLetterSummaryView::deadLetterPartition).reversed())
                    .thenComparing(Comparator.comparingLong(
                            MonitorDeadLetterSummaryView::deadLetterOffset).reversed()));
            return summaries.stream().limit(limit).toList();
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "could not list Kafka dead-letter records",
                    ex
            );
        }
    }

    /** 不可解析、归属不明或主题元数据不一致的记录不会进入项目列表。 */
    private MonitorDeadLetterSummaryView toSummary(
            MonitorProject project,
            String sourceTopic,
            MonitorEventType expectedType,
            ConsumerRecord<String, String> record) {
        MonitorEventEnvelope event;
        try {
            event = objectMapper.readValue(record.value(), MonitorEventEnvelope.class);
        } catch (JsonProcessingException ex) {
            return null;
        } catch (IllegalArgumentException ex) {
            // Jackson 收到 null 内容时会抛出参数异常；把这类无法解析的记录也从项目列表中过滤掉。
            return null;
        }
        if (!String.valueOf(project.getId()).equals(event.getProjectId())
                || event.getEventType() != expectedType
                || !sourceTopic.equals(readTextHeader(record, KafkaHeaders.DLT_ORIGINAL_TOPIC))) {
            return null;
        }

        try {
            int originalPartition = readIntegerHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
            long originalOffset = readLongHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);
            return new MonitorDeadLetterSummaryView(
                    sourceTopic,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    Math.max(record.timestamp(), 0),
                    originalPartition,
                    originalOffset,
                    event.getEventId(),
                    event.getEventType(),
                    readTextHeader(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
                    truncate(readTextHeader(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
            );
        } catch (ResponseStatusException ex) {
            // 缺少原始位置头的异常记录无法确认来源位置，不能安全地展示给项目 Owner 或执行重放。
            return null;
        }
    }

    // cd 查看死信记录（不重放，仅查看）通过接口展示
    public MonitorDeadLetterRecordView inspect(
            MonitorProject project, String sourceTopic, int partition, long offset) {
        // 查看与重放共用同一套校验，避免查看时能看到、重放时却误操作到其他项目的消息。
        DeadLetterRecord deadLetter = readAndValidate(project, sourceTopic, partition, offset);
        ReplayStatus replayStatus = readReplayStatus(sourceTopic, partition, offset);
        return toView(deadLetter, replayStatus.replayed(),
                replayStatus.targetPartition(), replayStatus.targetOffset());
    }

    public MonitorDeadLetterRecordView replay(MonitorProject project, MonitorDeadLetterReplayRequest request) {
        String sourceTopic = request.getSourceTopic();
        int deadLetterPartition = request.getPartition();
        long deadLetterOffset = request.getOffset();
        DeadLetterRecord deadLetter = readAndValidate(
                project, sourceTopic, deadLetterPartition, deadLetterOffset);
        validateReplayableEvent(deadLetter.event());

        // 同一个死信偏移量只允许在短期内发起一次重放；事件本身也会由现有 eventId 幂等逻辑保护。
        String replayKey = replayKey(sourceTopic, deadLetterPartition, deadLetterOffset);
        Boolean firstReplay = redisTemplate.opsForValue()
                .setIfAbsent(replayKey, "sending", REPLAY_IN_PROGRESS_TTL);
        if (!Boolean.TRUE.equals(firstReplay)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "this dead-letter record has already been replayed recently");
        }

        try {
            // DLT 记录带有原始分区编号；重放时发回该分区，保持原 topic 的分区顺序语义。
            ProducerRecord<String, String> replayRecord = new ProducerRecord<>(
                    sourceTopic,
                    deadLetter.originalPartition(),
                    deadLetter.record().key(),
                    deadLetter.record().value()
            );
            Header traceparent = deadLetter.record().headers().lastHeader("traceparent");
            if (traceparent != null) {
                // 只保留业务链路上下文，不把死信异常堆栈等内部错误头带回正常事件队列。
                replayRecord.headers().add(new RecordHeader("traceparent", traceparent.value().clone()));
            }
            // cd： 发送到原topic，保持分区顺序语义
            SendResult<String, String> result = kafkaTemplate.send(replayRecord)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int targetPartition = result.getRecordMetadata().partition();
            long targetOffset = result.getRecordMetadata().offset();
            redisTemplate.opsForValue().set(
                    replayKey, "sent:" + targetPartition + ":" + targetOffset, REPLAY_COMPLETED_TTL);
            return toView(
                    deadLetter,
                    true,
                    targetPartition,
                    targetOffset
            );
        } catch (Exception ex) {
            // 发送未得到确认时允许 Owner 稍后重试；若只是确认超时但 Kafka 已收到，事件编号仍由消费端幂等。
            redisTemplate.delete(replayKey);
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Kafka did not confirm the dead-letter replay",
                    ex
            );
        }
    }

    private DeadLetterRecord readAndValidate(
            MonitorProject project, String sourceTopic, int partition, long offset) {
        // 主题只允许来自服务端配置的监控主题映射，客户端不能借接口读取任意 Kafka 主题。
        MonitorEventType expectedType = sourceTopics.get(sourceTopic);
        if (expectedType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported monitor source topic");
        }
        if (partition < 0 || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partition and offset must be non-negative");
        }

        // 当前错误处理器把失败消息发到“原主题名 + .dlq”，所以从对应死信主题定位记录。
        String deadLetterTopic = sourceTopic + ".dlq";
        TopicPartition deadLetterPartition = new TopicPartition(deadLetterTopic, partition);
        ConsumerRecord<String, String> record = readRecord(deadLetterTopic, deadLetterPartition, offset);
        // Spring Kafka 在死信头里保留原主题和原分区；重放必须回到原主题的原分区。
        int originalPartition = readIntegerHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        long originalOffset = readLongHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);
        String originalTopic = readTextHeader(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (!sourceTopic.equals(originalTopic) || originalPartition < 0 || originalOffset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dead-letter record does not belong to the requested source topic");
        }

        // 项目 ID 是从原始事件 JSON 读取的；只有与 URL 中项目一致才允许查看或重放。
        MonitorEventEnvelope event = parseEvent(record.value());
        if (!String.valueOf(project.getId()).equals(event.getProjectId())) {
            // 返回 404 而不是 403，避免泄露别的项目是否有这条死信。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dead-letter record not found");
        }
        if (event.getEventType() != expectedType) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dead-letter event type does not match its source topic");
        }
        return new DeadLetterRecord(sourceTopic, record, originalPartition, originalOffset, event);
    }

    private ConsumerRecord<String, String> readRecord(
            String topic, TopicPartition partition, long offset) {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer(
                null,
                "monitor-dlq-admin",
                UUID.randomUUID().toString(),
                consumerOverrides())) {
            List<PartitionInfo> partitions = consumer.partitionsFor(topic, KAFKA_REQUEST_TIMEOUT);
            if (partitions == null || partitions.stream().noneMatch(info -> info.partition() == partition.partition())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dead-letter record not found");
            }
            Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(List.of(partition), KAFKA_REQUEST_TIMEOUT);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(List.of(partition), KAFKA_REQUEST_TIMEOUT);
            long beginning = beginningOffsets.getOrDefault(partition, 0L);
            long end = endOffsets.getOrDefault(partition, beginning);
            // Kafka 的可读位置是 [beginning, end)；范围外的位置已过期或从未存在。
            if (offset < beginning || offset >= end) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dead-letter record not found");
            }

            consumer.assign(List.of(partition));
            // assign 表示这次临时读取自己指定分区，不加入正常消费者的分区分配流程。
            consumer.seek(partition, offset);
            long deadline = System.nanoTime() + RECORD_LOOKUP_TIMEOUT_NANOS;
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                for (ConsumerRecord<String, String> record : records.records(partition)) {
                    if (record.offset() == offset) {
                        // 只有精确偏移量命中才返回，不能把相邻的另一条死信误当成目标消息。
                        return record;
                    }
                    if (record.offset() > offset) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dead-letter record not found");
                    }
                }
                if (consumer.position(partition) > offset) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dead-letter record not found");
                }
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dead-letter record not found");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "could not read the Kafka dead-letter record",
                    ex
            );
        }
    }

    private MonitorEventEnvelope parseEvent(String payload) {
        try {
            return objectMapper.readValue(payload, MonitorEventEnvelope.class);
        } catch (JsonProcessingException ex) {
            // 无法解析就无法证明消息属于哪个项目，因此不能把内容返回给调用方或自动重放。
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "dead-letter payload cannot be safely associated with a project",
                    ex
            );
        }
    }

    private void validateReplayableEvent(MonitorEventEnvelope event) {
        // 这几个字段是消费端写入和幂等判断的基础；缺失时重放只会再次失败。
        if (event.getEventId() == null || event.getEventId().isBlank()
                || event.getTimestamp() == null
                || event.getData() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "dead-letter event is missing required fields and cannot be replayed safely"
            );
        }
    }

    private MonitorDeadLetterRecordView toView(
            DeadLetterRecord deadLetter,
            boolean replayed,
            Integer targetPartition,
            Long targetOffset) {
        ConsumerRecord<String, String> record = deadLetter.record();
        // 查看和重放共用一个响应结构；重放结果额外带回原主题写入后的分区与偏移量。
        return new MonitorDeadLetterRecordView(
                deadLetter.sourceTopic(),
                record.topic(),
                record.partition(),
                record.offset(),
                record.timestamp(),
                deadLetter.originalPartition(),
                deadLetter.originalOffset(),
                deadLetter.event().getEventId(),
                deadLetter.event().getEventType(),
                readTextHeader(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
                truncate(readTextHeader(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE)),
                record.value(),
                replayed,
                targetPartition,
                targetOffset
        );
    }

    private int readIntegerHeader(ConsumerRecord<String, String> record, String headerName) {
        Header header = record.headers().lastHeader(headerName);
        if (header == null || header.value() == null || header.value().length != Integer.BYTES) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "dead-letter record is missing its original partition metadata");
        }
        return ByteBuffer.wrap(header.value()).getInt();
    }

    private long readLongHeader(ConsumerRecord<String, String> record, String headerName) {
        Header header = record.headers().lastHeader(headerName);
        if (header == null || header.value() == null || header.value().length != Long.BYTES) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "dead-letter record is missing its original offset metadata");
        }
        return ByteBuffer.wrap(header.value()).getLong();
    }

    private String readTextHeader(ConsumerRecord<String, String> record, String headerName) {
        Header header = record.headers().lastHeader(headerName);
        return header == null || header.value() == null
                ? null
                : new String(header.value(), StandardCharsets.UTF_8);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= FAILURE_MESSAGE_LIMIT) {
            return value;
        }
        return value.substring(0, FAILURE_MESSAGE_LIMIT);
    }

    private String replayKey(String sourceTopic, int partition, long offset) {
        return "monitor:dlq:replayed:" + sourceTopic + ":" + partition + ":" + offset;
    }

    private ReplayStatus readReplayStatus(String sourceTopic, int partition, long offset) {
        String value = redisTemplate.opsForValue().get(replayKey(sourceTopic, partition, offset));
        if (value == null || "sending".equals(value)) {
            return new ReplayStatus(false, null, null);
        }
        if (value.startsWith("sent:")) {
            String[] parts = value.split(":", -1);
            if (parts.length == 3) {
                try {
                    return new ReplayStatus(true, Integer.valueOf(parts[1]), Long.valueOf(parts[2]));
                } catch (NumberFormatException ignored) {
                    // 兼容 Redis 中意外损坏的状态值：仍然禁止重复重放，但不显示未知的目标位置。
                }
            }
        }
        // 兼容此前只写入 "sent" 的状态值；可以标记已重放，但没有目标偏移量可展示。
        return new ReplayStatus(true, null, null);
    }

    private Properties consumerOverrides() {
        // 查看和重放只手动定位 offset；关闭自动提交，避免碰业务消费者组的消费进度。
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return overrides;
    }

    private record DeadLetterRecord(
            String sourceTopic,
            ConsumerRecord<String, String> record,
            int originalPartition,
            long originalOffset,
            MonitorEventEnvelope event
    ) {
    }

    private record ReplayStatus(boolean replayed, Integer targetPartition, Long targetOffset) {
    }
}
