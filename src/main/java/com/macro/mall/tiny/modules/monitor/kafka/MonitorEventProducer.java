package com.macro.mall.tiny.modules.monitor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

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
            kafkaTemplate.send(resolveTopic(event.getEventType()), event.getProjectId(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("monitor event serialization failed", e);
        }
    }

    private String resolveTopic(MonitorEventType type) {
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
