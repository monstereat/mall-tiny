package com.macro.mall.tiny.modules.monitor.config;

import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.apache.kafka.clients.admin.NewTopic;

import java.util.List;
import java.util.stream.Stream;

/** 启动时创建监控死信主题，并为每个主题明确设置保留策略。 */
@Configuration
public class MonitorDeadLetterTopicConfiguration {

    /**
     * KafkaAdmin 会在应用启动时创建这些主题；如果主题已存在，Spring Kafka 会按配置检查并调整主题配置。
     * 分区数必须不小于对应正常主题，错误处理器才可以把失败消息写回相同编号的分区。
     */
    @Bean
    public KafkaAdmin.NewTopics monitorDeadLetterTopics(
            @Value("${monitor.kafka.topics.error}") String errorTopic,
            @Value("${monitor.kafka.topics.performance}") String performanceTopic,
            @Value("${monitor.kafka.topics.behavior}") String behaviorTopic,
            @Value("${monitor.kafka.topics.replay}") String replayTopic,
            @Value("${monitor.kafka.topics.metric}") String metricTopic,
            @Value("${monitor.kafka.topics.profile}") String profileTopic,
            @Value("${monitor.kafka.topics.span:monitor-span-v1}") String spanTopic,
            @Value("${monitor.kafka.dlq.partitions:1}") int partitions,
            @Value("${monitor.kafka.dlq.replication-factor:1}") int replicationFactor,
            @Value("${monitor.kafka.dlq.retention-ms:2592000000}") long retentionMs) {
        if (partitions < 1 || replicationFactor < 1 || retentionMs < 1) {
            throw new IllegalArgumentException(
                    "Kafka dead-letter partitions, replication factor, and retention must be positive");
        }

        List<NewTopic> deadLetterTopics = Stream.of(
                        errorTopic, performanceTopic, behaviorTopic, replayTopic,
                        metricTopic, profileTopic, spanTopic)
                .distinct()
                .map(sourceTopic -> TopicBuilder.name(sourceTopic + ".dlq")
                        .partitions(partitions)
                        .replicas(replicationFactor)
                        .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retentionMs))
                        .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                        .build())
                .toList();
        return new KafkaAdmin.NewTopics(deadLetterTopics.toArray(NewTopic[]::new));
    }
}
