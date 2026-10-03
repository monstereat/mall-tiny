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
import static org.mockito.Mockito.*;

class MonitorIngestServiceSpanTest {
    private final MonitorProjectService projects = mock(MonitorProjectService.class);
    private final MonitorRateLimiter rateLimiter = mock(MonitorRateLimiter.class);
    private final MonitorEventProducer producer = mock(MonitorEventProducer.class);
    private final MonitorMetricCardinalityService cardinality = mock(MonitorMetricCardinalityService.class);
    private final MonitorIngestService service = new MonitorIngestService(
            projects, rateLimiter, producer, cardinality, new MonitorEventScrubber());

    @Test
    void acceptsValidSpanAndCanonicalizesIdsAndEventTime() {
        MonitorProject project = new MonitorProject();
        project.setId(7L);
        when(projects.validateIngestKey("project-a", "key")).thenReturn(project);
        when(rateLimiter.tryAcquire("project-a", 1)).thenReturn(true);
        MonitorEventEnvelope event = event("0123456789ABCDEF0123456789ABCDEF",
                "0123456789ABCDEF", "FEDCBA9876543210", 0, "ok");

        service.ingest("key", event);

        assertEquals("0123456789abcdef0123456789abcdef", event.getTraceId());
        assertEquals("0123456789abcdef", event.getData().get("spanId"));
        assertEquals("fedcba9876543210", event.getData().get("parentSpanId"));
        assertEquals(((Number) event.getData().get("startTime")).longValue(), event.getTimestamp());
        verify(producer).publish(event);
    }

    @Test
    void rejectsInvalidTraceIdAndOversizedDurationBeforeKafkaPublish() {
        MonitorEventEnvelope badTrace = event("00000000000000000000000000000000",
                "0123456789abcdef", null, 10, "ok");
        ResponseStatusException traceError = assertThrows(ResponseStatusException.class,
                () -> service.ingest("key", badTrace));
        assertEquals(400, traceError.getStatusCode().value());

        MonitorEventEnvelope badDuration = event("0123456789abcdef0123456789abcdef",
                "0123456789abcdef", null, 86_400_001, "error");
        ResponseStatusException durationError = assertThrows(ResponseStatusException.class,
                () -> service.ingest("key", badDuration));
        assertEquals(400, durationError.getStatusCode().value());
        verifyNoInteractions(producer);
    }

    private MonitorEventEnvelope event(String traceId, String spanId, String parentSpanId,
                                       double durationMs, String status) {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventId("span-1");
        event.setProjectId("project-a");
        event.setEventType(MonitorEventType.SPAN);
        event.setTimestamp(System.currentTimeMillis());
        event.setTraceId(traceId);
        Map<String, Object> data = new HashMap<>();
        data.put("spanId", spanId);
        if (parentSpanId != null) data.put("parentSpanId", parentSpanId);
        data.put("op", "http.client");
        data.put("description", "GET /checkout");
        data.put("startTime", System.currentTimeMillis());
        data.put("durationMs", durationMs);
        data.put("status", status);
        data.put("statusCode", 0);
        event.setData(data);
        return event;
    }
}
