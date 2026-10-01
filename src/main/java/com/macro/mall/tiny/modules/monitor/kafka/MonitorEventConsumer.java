package com.macro.mall.tiny.modules.monitor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorReplay;
import com.macro.mall.tiny.modules.monitor.repository.ClickHouseEventRepository;
import com.macro.mall.tiny.modules.monitor.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MonitorEventConsumer {

    private final ObjectMapper objectMapper;
    private final ClickHouseEventRepository repository;
    private final ErrorFingerprintService fingerprintService;
    private final MonitorProjectService projectService;
    private final MonitorIssueService issueService;
    private final MonitorEventDeduplicator deduplicator;
    private final MonitorReplayService replayService;
    private final MonitorAlertEngine alertEngine;

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

            if (expectedType == MonitorEventType.REPLAY) {
                MonitorReplay replay = replayService.store(project, event);
                Map<String, Object> replayRef = new LinkedHashMap<>();
                replayRef.put("replayId", replay.getId());
                replayRef.put("eventCount", replay.getEventCount());
                replayRef.put("sessionId", replay.getSessionId());
                replayRef.put("format", "rrweb");
                event.setData(replayRef);
            }

            repository.save(event, fingerprint);

            if (expectedType == MonitorEventType.ERROR) {
                issueService.aggregate(project, event, fingerprint);
                alertEngine.evaluate(project, event, fingerprint);
            } else if (expectedType == MonitorEventType.PERFORMANCE) {
                alertEngine.evaluate(project, event, "");
            }
        } catch (RuntimeException ex) {
            // 发生临时故障时释放幂等键，让 Kafka Retry/DLQ 接管。
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
