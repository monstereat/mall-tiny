package com.macro.mall.tiny.modules.monitor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.repository.ClickHouseEventRepository;
import com.macro.mall.tiny.modules.monitor.service.ErrorFingerprintService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MonitorEventConsumer {

    private final ObjectMapper objectMapper;
    private final ClickHouseEventRepository repository;
    private final ErrorFingerprintService fingerprintService;

    @KafkaListener(topics = "${monitor.kafka.topics.error}")
    public void consumeError(String payload) {
        persist(payload, MonitorEventType.ERROR);
    }

    @KafkaListener(topics = "${monitor.kafka.topics.performance}")
    public void consumePerformance(String payload) {
        persist(payload, MonitorEventType.PERFORMANCE);
    }

    @KafkaListener(topics = "${monitor.kafka.topics.behavior}")
    public void consumeBehavior(String payload) {
        persist(payload, MonitorEventType.BEHAVIOR);
    }

    @KafkaListener(topics = "${monitor.kafka.topics.replay}")
    public void consumeReplay(String payload) {
        persist(payload, MonitorEventType.REPLAY);
    }

    private void persist(String payload, MonitorEventType expectedType) {
        try {
            MonitorEventEnvelope event = objectMapper.readValue(payload, MonitorEventEnvelope.class);
            if (event.getEventType() != expectedType) {
                throw new IllegalArgumentException("event type does not match topic: " + expectedType);
            }
            String fingerprint = expectedType == MonitorEventType.ERROR
                    ? fingerprintService.generate(event)
                    : "";
            repository.save(event, fingerprint);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid monitor event payload", e);
        }
    }
}
