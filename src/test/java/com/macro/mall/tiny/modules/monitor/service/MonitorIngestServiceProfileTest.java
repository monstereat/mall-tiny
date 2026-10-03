package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.kafka.MonitorEventProducer;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MonitorIngestServiceProfileTest {
    private final MonitorProjectService projects = mock(MonitorProjectService.class);
    private final MonitorRateLimiter rateLimiter = mock(MonitorRateLimiter.class);
    private final MonitorEventProducer producer = mock(MonitorEventProducer.class);
    private final MonitorMetricCardinalityService cardinality = mock(MonitorMetricCardinalityService.class);
    private final MonitorIngestService service = new MonitorIngestService(projects, rateLimiter, producer, cardinality);

    @Test
    void acceptsCollapsedCpuProfile() {
        MonitorProject project = new MonitorProject();
        project.setId(7L);
        when(projects.validateIngestKey("project-a", "key")).thenReturn(project);
        when(rateLimiter.tryAcquire("project-a", 1)).thenReturn(true);
        MonitorEventEnvelope event = event(Map.of("format", "collapsed", "name", "CPU", "unit", "samples",
                "samples", List.of(Map.of("stack", List.of("onClick", "render"), "value", 3))));

        service.ingest("key", event);

        verify(producer).publish(event);
    }

    @Test
    void rejectsInvalidProfileSampleBeforePublishing() {
        MonitorEventEnvelope event = event(Map.of("format", "collapsed", "name", "CPU", "unit", "samples",
                "samples", List.of(Map.of("stack", List.of("onClick"), "value", Double.NaN))));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.ingest("key", event));

        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(producer);
    }

    private MonitorEventEnvelope event(Map<String, Object> data) {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventId("profile-1");
        event.setProjectId("project-a");
        event.setEventType(MonitorEventType.PROFILE);
        event.setTimestamp(System.currentTimeMillis());
        event.setData(new HashMap<>(data));
        return event;
    }
}
