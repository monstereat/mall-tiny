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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MonitorEventConsumer {

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

    @KafkaListener(topics = "${monitor.kafka.topics.error}")
    public void consumeError(List<String> payloads) {
        persistBatch(payloads, MonitorEventType.ERROR);
    }

    @KafkaListener(topics = "${monitor.kafka.topics.performance}")
    public void consumePerformance(List<String> payloads) {
        persistBatch(payloads, MonitorEventType.PERFORMANCE);
    }

    @KafkaListener(topics = "${monitor.kafka.topics.behavior}")
    public void consumeBehavior(List<String> payloads) {
        persistBatch(payloads, MonitorEventType.BEHAVIOR);
    }

    @KafkaListener(topics = "${monitor.kafka.topics.replay}")
    public void consumeReplay(List<String> payloads) {
        persistBatch(payloads, MonitorEventType.REPLAY);
    }

    private void persistBatch(List<String> payloads, MonitorEventType expectedType) {
        if (payloads == null || payloads.isEmpty()) {
            return;
        }

        List<PreparedEvent> prepared = new ArrayList<>();
        List<String> reservedEventIds = new ArrayList<>();

        try {
            for (String payload : payloads) {
                MonitorEventEnvelope event = parse(payload);
                if (event.getEventType() != expectedType) {
                    throw new IllegalArgumentException("event type does not match topic: " + expectedType);
                }
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
                    MonitorReplay replay = replayService.store(project, event);
                    Map<String, Object> replayRef = new LinkedHashMap<>();
                    replayRef.put("replayId", replay.getId());
                    replayRef.put("eventCount", replay.getEventCount());
                    replayRef.put("sessionId", replay.getSessionId());
                    replayRef.put("format", "rrweb");
                    event.setData(replayRef);
                }

                prepared.add(new PreparedEvent(event, project, fingerprint));
            }

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
                } else if (expectedType == MonitorEventType.PERFORMANCE) {
                    alertEngine.evaluate(item.project(), item.event(), "");
                }
            }
        } catch (RuntimeException ex) {
            // At-least-once delivery: release batch reservations and allow Kafka retry/DLQ.
            // ClickHouse uses ReplacingMergeTree(event_id) so raw-event retries are dedupe-friendly.
            reservedEventIds.forEach(deduplicator::release);
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
