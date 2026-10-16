package com.macro.mall.tiny.modules.monitor.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 定时查看死信主题有没有积压，并把结果暴露给 Prometheus。
 * 这里不读取或输出业务内容，只统计消息数量和最早一条消息已经等待了多久。
 */
@Slf4j
@Component
public class MonitorDeadLetterMetrics {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FIRST_RECORD_POLL_TIMEOUT = Duration.ofMillis(200);
    private static final long OLDEST_RECORD_LOOKUP_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3);

    private final KafkaAdmin kafkaAdmin;
    private final ConsumerFactory<String, String> consumerFactory;
    private final Map<String, TopicMetrics> topicMetrics = new HashMap<>();
    // 启动后第一次刷新完成之前，不能先报“采集成功”。
    private final AtomicLong collectionUp = new AtomicLong(0);

    public MonitorDeadLetterMetrics(
            KafkaAdmin kafkaAdmin,
            ConsumerFactory<String, String> consumerFactory,
            MeterRegistry meterRegistry,
            @Value("${monitor.kafka.topics.error}") String errorTopic,
            @Value("${monitor.kafka.topics.performance}") String performanceTopic,
            @Value("${monitor.kafka.topics.behavior}") String behaviorTopic,
            @Value("${monitor.kafka.topics.replay}") String replayTopic,
            @Value("${monitor.kafka.topics.metric}") String metricTopic,
            @Value("${monitor.kafka.topics.profile}") String profileTopic,
            @Value("${monitor.kafka.topics.span:monitor-span-v1}") String spanTopic) {
        this.kafkaAdmin = kafkaAdmin;
        this.consumerFactory = consumerFactory;
        List.of(errorTopic, performanceTopic, behaviorTopic, replayTopic, metricTopic, profileTopic, spanTopic)
                .stream()
                .distinct()
                .map(topic -> topic + ".dlq")
                .forEach(topic -> registerTopicMetrics(meterRegistry, topic));
        Gauge.builder("monitor.kafka.dlq.metrics.up", collectionUp, AtomicLong::get)
                .description("Whether the most recent Kafka dead-letter metrics collection succeeded")
                .register(meterRegistry);
    }

    private void registerTopicMetrics(MeterRegistry meterRegistry, String topic) {
        TopicMetrics metrics = new TopicMetrics();
        topicMetrics.put(topic, metrics);
        Gauge.builder("monitor.kafka.dlq.records", metrics.records, AtomicLong::get)
                .description("Approximate number of records waiting in a monitor dead-letter topic")
                .tag("topic", topic)
                .register(meterRegistry);
        Gauge.builder("monitor.kafka.dlq.oldest.message.age.seconds", metrics.oldestAgeSeconds, AtomicLong::get)
                .description("Age in seconds of the oldest record; -1 means the age could not be read")
                .tag("topic", topic)
                .register(meterRegistry);
    }

    /** 每隔一段时间刷新一次，避免 Prometheus 每次抓取都直接请求 Kafka。 */
    @Scheduled(fixedDelayString = "${monitor.kafka.dlq.metrics-refresh-ms:30000}")
    public void refresh() {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());
             Consumer<String, String> consumer = consumerFactory.createConsumer(
                     null,
                     "monitor-dlq-metrics",
                     UUID.randomUUID().toString(),
                     consumerOverrides())) {
            Set<String> existingTopics = adminClient.listTopics()
                    .names()
                    .get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            for (Map.Entry<String, TopicMetrics> entry : topicMetrics.entrySet()) {
                if (!existingTopics.contains(entry.getKey())) {
                    // 主题通常在第一条消息进入死信时才创建；不存在就表示当前没有死信记录。
                    entry.getValue().update(0, 0);
                    continue;
                }
                entry.getValue().update(readTopicMetrics(adminClient, consumer, entry.getKey()));
            }
            collectionUp.set(1);
        } catch (Exception ex) {
            // 采集失败时保留上次读到的数量，但单独将采集状态置为 0，避免把旧值误认为实时值。
            collectionUp.set(0);
            log.warn("Could not refresh monitor Kafka dead-letter metrics", ex);
        }
    }

    private TopicSnapshot readTopicMetrics(
            AdminClient adminClient,
            Consumer<String, String> consumer,
            String topic) throws Exception {
        TopicDescription description = adminClient.describeTopics(List.of(topic))
                .allTopicNames()
                .get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .get(topic);
        List<TopicPartition> partitions = description.partitions().stream()
                .map(TopicPartitionInfo::partition)
                .map(partition -> new TopicPartition(topic, partition))
                .toList();
        if (partitions.isEmpty()) {
            return new TopicSnapshot(0, 0);
        }

        Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions, REQUEST_TIMEOUT);
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions, REQUEST_TIMEOUT);
        long records = 0;
        Set<TopicPartition> partitionsWithRecords = new HashSet<>();
        for (TopicPartition partition : partitions) {
            long beginning = beginningOffsets.getOrDefault(partition, 0L);
            long end = endOffsets.getOrDefault(partition, beginning);
            if (end > beginning) {
                records += end - beginning;
                partitionsWithRecords.add(partition);
            }
        }
        if (records == 0) {
            return new TopicSnapshot(0, 0);
        }

        // 只读每个有积压分区的第一条消息，用它估算最老死信等待时间。
        consumer.assign(new ArrayList<>(partitionsWithRecords));
        for (TopicPartition partition : partitionsWithRecords) {
            consumer.seek(partition, beginningOffsets.get(partition));
        }
        Map<TopicPartition, ConsumerRecord<String, String>> firstRecords = new HashMap<>();
        long deadline = System.nanoTime() + OLDEST_RECORD_LOOKUP_TIMEOUT_NANOS;
        while (firstRecords.size() < partitionsWithRecords.size() && System.nanoTime() < deadline) {
            ConsumerRecords<String, String> polled = consumer.poll(FIRST_RECORD_POLL_TIMEOUT);
            for (ConsumerRecord<String, String> record : polled) {
                firstRecords.merge(
                        new TopicPartition(record.topic(), record.partition()),
                        record,
                        (current, candidate) -> candidate.offset() < current.offset() ? candidate : current
                );
            }
        }

        if (firstRecords.size() < partitionsWithRecords.size()) {
            // 如果 Kafka 没能读到每个积压分区的头一条消息，就不猜年龄，使用 -1 表示未知。
            return new TopicSnapshot(records, -1);
        }
        long oldestTimestamp = firstRecords.values().stream()
                .mapToLong(ConsumerRecord::timestamp)
                .filter(timestamp -> timestamp >= 0)
                .min()
                .orElse(-1L);
        long ageSeconds = oldestTimestamp < 0
                ? -1
                : Math.max(0, (System.currentTimeMillis() - oldestTimestamp) / 1000);
        return new TopicSnapshot(records, ageSeconds);
    }

    private static final class TopicMetrics {
        private final AtomicLong records = new AtomicLong();
        private final AtomicLong oldestAgeSeconds = new AtomicLong();

        private void update(TopicSnapshot snapshot) {
            update(snapshot.records(), snapshot.oldestAgeSeconds());
        }

        private void update(long recordCount, long oldestAge) {
            records.set(recordCount);
            oldestAgeSeconds.set(oldestAge);
        }
    }

    private Properties consumerOverrides() {
        // 指标采集只手动读取死信主题，不参与提交业务消费者组的 offset。
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return overrides;
    }

    private record TopicSnapshot(long records, long oldestAgeSeconds) {
    }
}
