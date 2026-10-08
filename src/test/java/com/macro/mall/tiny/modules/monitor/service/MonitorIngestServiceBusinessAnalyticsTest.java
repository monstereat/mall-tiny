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

class MonitorIngestServiceBusinessAnalyticsTest {

    private final MonitorProjectService projectService = mock(MonitorProjectService.class);
    private final MonitorRateLimiter rateLimiter = mock(MonitorRateLimiter.class);
    private final MonitorEventProducer producer = mock(MonitorEventProducer.class);
    private final MonitorMetricCardinalityService cardinality = mock(MonitorMetricCardinalityService.class);
    private final MonitorIngestService service = new MonitorIngestService(
            projectService, rateLimiter, producer, cardinality, new MonitorEventScrubber());

    @Test
    void acceptsSampledBusinessEventsAndScrubsPropertiesWithoutChangingCategory() {
        MonitorEventEnvelope event = event("business", Map.of(
                "event", "register_click",
                "analyticsSampleRate", 0.25,
                "properties", Map.of("page_name", "register", "token", "secret-value")));
        prepareIngest();

        service.ingest("ingest-key", event);

        verify(producer).publish(event);
        assertEquals("business", event.getData().get("category"));
        assertEquals(0.25, event.getData().get("analyticsSampleRate"));
        assertEquals("[Filtered]", ((Map<?, ?>) event.getData().get("properties")).get("token"));
    }

    @Test
    void acceptsPageViewAndIncrementalDwellEvents() {
        MonitorEventEnvelope pageView = event("page", Map.of(
                "event", "page_view",
                "pageViewId", "a4f170b3-940f-4ab4-a054-a04fa6d770b5",
                "analyticsSampleRate", 1,
                "name", "checkout",
                "referrer", "https://example.test/cart"));
        MonitorEventEnvelope dwell = event("page", Map.of(
                "event", "page_dwell",
                "pageViewId", "a4f170b3-940f-4ab4-a054-a04fa6d770b5",
                "analyticsSampleRate", 1,
                "durationMs", 1250));
        prepareIngest();

        service.ingest("ingest-key", pageView);
        service.ingest("ingest-key", dwell);

        verify(producer).publish(pageView);
        verify(producer).publish(dwell);
    }

    @Test
    void keepsExistingApiAndSessionBehaviorCategoriesValid() {
        MonitorEventEnvelope api = event("api", Map.of("url", "/checkout", "status", 200));
        MonitorEventEnvelope session = event("session", Map.of("action", "start"));
        prepareIngest();

        service.ingest("ingest-key", api);
        service.ingest("ingest-key", session);

        verify(producer).publish(api);
        verify(producer).publish(session);
    }

    @Test
    void rejectsInvalidNamesSampleRatesAndDwellDurationsBeforePublishing() {
        MonitorEventEnvelope invalidName = event("business", Map.of(
                "event", "Register Click", "analyticsSampleRate", 1));
        MonitorEventEnvelope missingSampleRate = event("business", Map.of("event", "register_click"));
        MonitorEventEnvelope invalidPageView = event("page", Map.of(
                "event", "page_view", "pageViewId", "not-a-uuid", "analyticsSampleRate", 1.1));
        MonitorEventEnvelope invalidDwell = event("page", Map.of(
                "event", "page_dwell", "pageViewId", "view-1", "analyticsSampleRate", 1,
                "durationMs", 86_400_001));

        assertBadRequest(invalidName);
        assertBadRequest(missingSampleRate);
        assertBadRequest(invalidPageView);
        assertBadRequest(invalidDwell);

        verifyNoInteractions(producer);
    }

    @Test
    void rejectsOversizedAndDeepPropertiesBeforePublishing() {
        MonitorEventEnvelope oversized = event("business", Map.of(
                "event", "register_click",
                "analyticsSampleRate", 1,
                "properties", Map.of("large", "x".repeat(16_385))));
        Map<String, Object> tooDeepValue = Map.of("level2", Map.of("level3", Map.of("level4", "value")));
        MonitorEventEnvelope tooDeep = event("business", Map.of(
                "event", "register_click", "analyticsSampleRate", 1,
                "properties", Map.of("level1", tooDeepValue)));

        assertBadRequest(oversized);
        assertBadRequest(tooDeep);

        verifyNoInteractions(producer);
    }

    private void assertBadRequest(MonitorEventEnvelope event) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.ingest("ingest-key", event));
        assertEquals(400, exception.getStatusCode().value());
    }

    private void prepareIngest() {
        MonitorProject project = new MonitorProject();
        project.setId(42L);
        when(projectService.validateIngestKey("project-a", "ingest-key")).thenReturn(project);
        when(rateLimiter.tryAcquire("project-a", 1)).thenReturn(true);
    }

    private MonitorEventEnvelope event(String category, Map<String, Object> data) {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventId("event-1");
        event.setProjectId("project-a");
        event.setEventType(MonitorEventType.BEHAVIOR);
        event.setTimestamp(System.currentTimeMillis());
        event.setUserId("user-1");
        Map<String, Object> payload = new HashMap<>(data);
        payload.put("category", category);
        event.setData(payload);
        return event;
    }
}
