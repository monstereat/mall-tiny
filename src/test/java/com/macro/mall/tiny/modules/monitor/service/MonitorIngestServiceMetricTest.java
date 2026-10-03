package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.kafka.MonitorEventProducer;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class MonitorIngestServiceMetricTest {

    private final MonitorProjectService projectService = mock(MonitorProjectService.class);
    private final MonitorRateLimiter rateLimiter = mock(MonitorRateLimiter.class);
    private final MonitorEventProducer producer = mock(MonitorEventProducer.class);
    private final MonitorMetricCardinalityService metricCardinalityService = mock(MonitorMetricCardinalityService.class);
    private final MonitorIngestService service = new MonitorIngestService(
            projectService, rateLimiter, producer, metricCardinalityService);

    @Test
    void acceptsMetricSamplesWithBoundedDimensions() {
        MonitorProject project = project();
        MonitorEventEnvelope event = metric("checkout.duration", "distribution", 42.5, Map.of("region", "cn"));
        when(projectService.validateIngestKey("project-a", "ingest-key")).thenReturn(project);
        when(rateLimiter.tryAcquire("project-a", 1)).thenReturn(true);

        service.ingest("ingest-key", event);

        verify(projectService).validateIngestKey("project-a", "ingest-key");
        verify(metricCardinalityService).reserve(42L, java.util.List.of(event));
        verify(producer).publish(org.mockito.ArgumentMatchers.any(MonitorEventEnvelope.class));
    }

    @Test
    void rejectsCardinalityBeforePublishing() {
        when(projectService.validateIngestKey("project-a", "ingest-key")).thenReturn(project());
        org.mockito.Mockito.doThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "metric dimension-value cardinality limit exceeded"))
                .when(metricCardinalityService).reserve(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.anyList());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ingest("ingest-key", metric("checkout.count", "counter", 1, Map.of("region", "region-101")))
        );

        assertEquals(429, exception.getStatusCode().value());
        verifyNoInteractions(producer);
    }

    @Test
    void rejectsInvalidMetricBeforePublishing() {
        MonitorEventEnvelope event = metric("bad name", "counter", 1, Map.of());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ingest("ingest-key", event)
        );

        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(producer);
    }

    @Test
    void rejectsTooManyMetricDimensions() {
        Map<String, Object> tags = new HashMap<>();
        for (int i = 0; i < 21; i++) tags.put("tag" + i, "value");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ingest("ingest-key", metric("checkout.count", "counter", 1, tags))
        );

        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(producer);
    }

    private MonitorEventEnvelope metric(String name, String type, Number value, Map<String, ?> tags) {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventId("event-1");
        event.setProjectId("project-a");
        event.setEventType(MonitorEventType.METRIC);
        event.setTimestamp(System.currentTimeMillis());
        event.setData(new HashMap<>(Map.of(
                "name", name,
                "metricType", type,
                "value", value,
                "tags", tags
        )));
        return event;
    }

    private MonitorProject project() {
        MonitorProject project = new MonitorProject();
        project.setId(42L);
        return project;
    }
}
