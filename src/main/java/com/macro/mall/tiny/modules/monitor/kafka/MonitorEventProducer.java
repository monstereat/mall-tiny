package com.macro.mall.tiny.modules.monitor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 把事件交给 Kafka 队列的出口：先把 Java 对象转成 JSON，再按事件类型选择对应队列。
 * Spring 会提供 KafkaTemplate；send 发起异步发送后就会返回，后台消费者负责后续保存。
 */
@Component
@RequiredArgsConstructor
public class MonitorEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${monitor.kafka.topics.error}")
    private String errorTopic;

    @Value("${monitor.kafka.topics.performance}")
    private String performanceTopic;

    @Value("${monitor.kafka.topics.behavior}")
    private String behaviorTopic;

    @Value("${monitor.kafka.topics.replay}")
    private String replayTopic;

    @Value("${monitor.kafka.topics.metric}")
    private String metricTopic;

    @Value("${monitor.kafka.topics.profile}")
    private String profileTopic;

    @Value("${monitor.kafka.topics.span:monitor-span-v1}")
    private String spanTopic;

    public void publish(MonitorEventEnvelope event) {
        try {
            // send 不会等数据库保存完成；同一项目使用同一个 key，Kafka 会把它们放到同一队列分区，尽量保持先后顺序。
            kafkaTemplate.send(resolveTopic(event.getEventType()), event.getProjectId(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("monitor event serialization failed", e);
        }
    }

    private String resolveTopic(MonitorEventType type) {
        // 队列名称只从事件类型的固定对应表中选，不能让请求内容决定消息发到哪里。
        return switch (type) {
            case ERROR -> errorTopic;
            case PERFORMANCE -> performanceTopic;
            case BEHAVIOR -> behaviorTopic;
            case REPLAY -> replayTopic;
            case METRIC -> metricTopic;
            case PROFILE -> profileTopic;
            case SPAN -> spanTopic;
        };
    }
}
