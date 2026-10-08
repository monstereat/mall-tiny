package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonitorQueryExploreNumericTest {
    private final JdbcTemplate clickHouse = mock(JdbcTemplate.class);
    private final MonitorQueryService service = new MonitorQueryService(clickHouse, null, null, null, null, null, null);
    private final Instant end = Instant.parse("2026-10-04T01:00:00Z");

    @Test
    void queriesAllFiniteNumericSamplesWithSharedWindowAndBoundFilters() {
        when(clickHouse.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row("production", 3L, 7.5, -2.5, 10.0)));
        var result = service.exploreNumericBuckets(project(), 24, "production'", "web", null,
                null, "user", "flow", "checkout", "environment", end);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("monitor.performance_event"));
        assertTrue(sql.getValue().contains("monitor.metric_event"));
        assertFalse(sql.getValue().contains("monitor.error_event"));
        assertTrue(sql.getValue().contains("IN ('Int64','UInt64','Double')"));
        assertTrue(sql.getValue().contains("isFinite(JSONExtractFloat"));
        assertTrue(sql.getValue().contains("event_time>? AND event_time<=?"));
        assertTrue(sql.getValue().contains("LIMIT 1001"));
        assertFalse(sql.getValue().contains("production'"));
        assertEquals(2, Arrays.stream(args.getValue()).filter(Timestamp.from(end)::equals).count());
        assertEquals(2, Arrays.stream(args.getValue()).filter("store-a"::equals).count());
        assertEquals(3L, result.get(0).count());
        assertEquals(-2.5, result.get(0).min());
        assertEquals(7.5, result.get(0).sum());
    }

    @Test
    void rejectsGroupOverflowInsteadOfTruncatingBeforeMerge() {
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(
                IntStream.range(0, 1001).mapToObj(i -> row("g" + i, 1L, 1, 1, 1)).toList());
        ResponseStatusException error = assertThrows(ResponseStatusException.class, this::query);
        assertEquals(422, error.getStatusCode().value());
    }

    @Test
    void rejectsNonFiniteResultsAndUnsupportedDimensionsOrWindows() {
        when(clickHouse.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row("g", 2L, Double.POSITIVE_INFINITY, 1, 1)));
        assertEquals(422, assertThrows(ResponseStatusException.class, this::query).getStatusCode().value());
        assertThrows(IllegalArgumentException.class, () -> service.exploreNumericBuckets(project(), 169,
                null, null, null, null, null, null, null, "signal", end));
        assertThrows(IllegalArgumentException.class, () -> service.exploreNumericBuckets(project(), 24,
                null, null, null, null, null, null, null, "tag.secret", end));
    }

    private List<?> query() {
        return service.exploreNumericBuckets(project(), 24, null, null, null, null, null, null, null, "signal", end);
    }

    private MonitorProject project() {
        MonitorProject project = new MonitorProject();
        project.setProjectKey("store-a");
        return project;
    }

    private Map<String, Object> row(String value, long count, double sum, double min, double max) {
        return Map.of("value", value, "count", count, "sum", sum, "min", min, "max", max);
    }
}
