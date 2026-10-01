package com.macro.mall.tiny.modules.monitor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.repository.ClickHouseEventRepository;
import com.macro.mall.tiny.modules.monitor.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MonitorEventConsumer {

    private final ObjectMapper objectMapper;
    private final ClickHouseEventRepository repository;
    private final ErrorFingerprintService fingerprintService;
    private final MonitorProjectService projectService;
    private final MonitorIssueService issueService;
    private final MonitorEventDeduplicator deduplicator;

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
        MonitorEventEnvelope event = parse(payload);
        if (event.getEventType() != expectedType) {
            throw new IllegalArgumentException("event type does not match topic: " + expectedType);
        }
        if (!deduplicator.reserve(event.getEventId())) {
            return;
        }

        try {
            MonitorProject project = projectService.getActiveProject(event.getProjectId());
            if (project == null) {
                throw new IllegalArgumentException("monitor project is disabled or missing: " + event.getProjectId());
            }

            String fingerprint = expectedType == MonitorEventType.ERROR
                    ? fingerprintService.generate(event)
                    : "";

            repository.save(event, fingerprint);

            if (expectedType == MonitorEventType.ERROR) {
                issueService.aggregate(project, event, fingerprint);
            }
        } catch (RuntimeException ex) {
            // Allow Kafka retry to process the event again after transient failures.
            deduplicator.release(event.getEventId());
            throw ex;
        }
    }

    private MonitorEventEnvelope parse(String payload) {
        try {
            return objectMapper.readValue(payload, MonitorEventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid monitor event payload", e);
        }
    }
}
