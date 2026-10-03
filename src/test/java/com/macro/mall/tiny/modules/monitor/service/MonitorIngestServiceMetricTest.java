package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.kafka.MonitorEventProducer;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;

class MonitorIngestServiceMetricTest {

    private final MonitorProjectService projectService = mock(MonitorProjectService.class);
    private final MonitorRateLimiter rateLimiter = mock(MonitorRateLimiter.class);
    private final MonitorEventProducer producer = mock(MonitorEventProducer.class);
    private final MonitorMetricCardinalityService metricCardinalityService = mock(MonitorMetricCardinalityService.class);
    private final MonitorIngestService service = new MonitorIngestService(
            projectService, rateLimiter, producer, metricCardinalityService, new MonitorEventScrubber());

    @Test
    void acceptsMetricSamplesWithBoundedDimensions() {
        MonitorProject project = project();
        project.setScrubEmails(true);
        project.setScrubCreditCards(true);
        project.setScrubIpAddresses(true);
        project.setScrubPhoneNumbers(true);
        project.setScrubChineseIdNumbers(true);
        MonitorEventEnvelope event = metric("checkout.duration", "distribution", 42.5,
                Map.of("region", "cn", "api_key", "metric-secret"));
        event.setPageUrl("https://203.0.113.8/?token=url-secret");
        event.getData().put("message", "Authorization: Bearer message-secret; alice@example.com; 4111 1111 1111 1111; from 2001:db8::1; 13800138000; 11010519491231002X");
        event.setDevice(Map.of("phone", "13912345678", "id", "11010519491231002X"));
        when(projectService.validateIngestKey("project-a", "ingest-key")).thenReturn(project);
        when(rateLimiter.tryAcquire("project-a", 1)).thenReturn(true);

        service.ingest("ingest-key", event);

        verify(projectService).validateIngestKey("project-a", "ingest-key");
        verify(metricCardinalityService).reserve(42L, java.util.List.of(event));
        verify(producer).publish(org.mockito.ArgumentMatchers.any(MonitorEventEnvelope.class));
        assertEquals("[Filtered]", ((Map<?, ?>) event.getData().get("tags")).get("api_key"));
        assertEquals("https://[Filtered]/?token=[Filtered]", event.getPageUrl());
        assertEquals("Authorization: [Filtered]; [Filtered]; [Filtered]; from [Filtered]; [Filtered]; [Filtered]",
                event.getData().get("message"));
        assertEquals(Map.of("phone", "[Filtered]", "id", "[Filtered]"), event.getDevice());
    }

    @Test
    void scopesEnvironmentAndReleaseMdcAroundPublishedEvent() {
        MonitorEventEnvelope event = metric("checkout.count", "counter", 1, Map.of());
        event.setEnvironment("staging");
        event.setRelease("web-42");
        when(projectService.validateIngestKey("project-a", "ingest-key")).thenReturn(project());
        when(rateLimiter.tryAcquire("project-a", 1)).thenReturn(true);
        doAnswer(invocation -> {
            assertEquals("staging", MDC.get("monitor.environment"));
            assertEquals("web-42", MDC.get("monitor.release"));
            return null;
        }).when(producer).publish(org.mockito.ArgumentMatchers.any(MonitorEventEnvelope.class));

        service.ingest("ingest-key", event);

        assertEquals(null, MDC.get("monitor.environment"));
        assertEquals(null, MDC.get("monitor.release"));
    }

    @Test
    void doesNotAssignSingleEnvironmentOrReleaseToMixedBatch() {
        MonitorEventEnvelope first = metric("checkout.count", "counter", 1, Map.of());
        first.setEnvironment("staging");
        first.setRelease("web-42");
        MonitorEventEnvelope second = metric("checkout.count", "counter", 2, Map.of());
        second.setEnvironment("production");
        second.setRelease("web-43");
        when(projectService.validateIngestKey("project-a", "ingest-key")).thenReturn(project());
        when(rateLimiter.tryAcquire("project-a", 2)).thenReturn(true);
        doAnswer(invocation -> {
            assertEquals(null, MDC.get("monitor.environment"));
            assertEquals(null, MDC.get("monitor.release"));
            return null;
        }).when(producer).publish(org.mockito.ArgumentMatchers.any(MonitorEventEnvelope.class));

        service.ingestBatch("ingest-key", List.of(first, second));

        assertEquals(null, MDC.get("monitor.environment"));
        assertEquals(null, MDC.get("monitor.release"));
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
