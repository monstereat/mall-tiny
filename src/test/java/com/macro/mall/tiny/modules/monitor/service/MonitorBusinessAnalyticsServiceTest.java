package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MonitorBusinessAnalyticsServiceTest {

    private final JdbcTemplate clickHouse = mock(JdbcTemplate.class);
    private final MonitorBusinessAnalyticsService service = new MonitorBusinessAnalyticsService(clickHouse);
    private final MonitorProject project = new MonitorProject();

    MonitorBusinessAnalyticsServiceTest() {
        project.setProjectKey("project-a");
    }

    @Test
    void returnsSummaryWithZeroAverageForAnEmptySource() {
        when(clickHouse.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
                "pv", 0L,
                "known_user_uv", 0L,
                "sessions", 0L,
                "business_event_count", 0L,
                "visible_dwell_ms", 0d,
                "excluded_sampled", 0L));
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        var result = service.query(project, 24, null, null, null, null);

        assertEquals(0L, result.summary().pv());
        assertEquals(0L, result.summary().knownUserUv());
        assertEquals(0L, result.summary().sessions());
        assertEquals(0L, result.summary().businessEventCount());
        assertEquals(0d, result.summary().visibleDwellMs());
        assertEquals(0d, result.summary().avgVisibleDwellMs());
        assertTrue(result.pages().isEmpty());
        assertTrue(result.events().isEmpty());
        verify(clickHouse).queryForMap(org.mockito.ArgumentMatchers.contains("category"), any(Object[].class));
    }

    @Test
    void parameterizesAllUserFiltersAndIncludesOnlyFullSampleInExactAnalytics() {
        when(clickHouse.queryForMap(anyString(), any(Object[].class))).thenReturn(summary(4, 2, 3, 5, 800d, 7));
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        String injectedPage = "checkout' OR 1=1 --";

        var result = service.query(project, 168, "staging", "web-42", injectedPage, "register_click");

        assertEquals(4L, result.summary().pv());
        assertEquals(7L, result.summary().excludedSampled());
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForMap(sql.capture(), arguments.capture());
        assertTrue(sql.getValue().contains("JSONExtractFloat(payload,'data','analyticsSampleRate')=1"));
        assertTrue(sql.getValue().contains("JSONExtractFloat(payload,'data','analyticsSampleRate')!=1"));
        assertFalse(sql.getValue().contains("analyticsSampleRate')analyticsSampleRate"));
        assertFalse(sql.getValue().contains("'page_view'"));
        assertFalse(sql.getValue().contains(injectedPage));
        assertFalse(sql.getValue().contains("register_click"));
        assertTrue(Arrays.asList(arguments.getValue()).contains("project-a"));
        assertTrue(Arrays.asList(arguments.getValue()).contains(injectedPage));
        assertTrue(Arrays.asList(arguments.getValue()).contains("register_click"));
        assertTrue(Arrays.asList(arguments.getValue()).contains("staging"));
        assertTrue(Arrays.asList(arguments.getValue()).contains("web-42"));

        org.mockito.ArgumentCaptor<String> listSql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(clickHouse, times(3)).queryForList(listSql.capture(), any(Object[].class));
        assertTrue(listSql.getAllValues().stream().allMatch(query ->
                query.contains("JSONExtractFloat(payload,'data','analyticsSampleRate')=1")));
    }

    @Test
    void limitsRankingsToOneHundredWithoutChangingFullSummary() {
        when(clickHouse.queryForMap(anyString(), any(Object[].class))).thenReturn(summary(900, 300, 120, 1400, 9000d, 25));
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("GROUP BY page_views.page_key")) {
                return rows(101, "page_url", "page_views", "unique_users", "sessions",
                        "visible_dwell_ms", "avg_visible_dwell_ms");
            }
            if (sql.contains("GROUP BY business_events.event_name")) {
                return rows(101, "event_name", "event_count", "unique_users", "sessions");
            }
            return List.of();
        });

        var result = service.query(project, 1, null, null, null, null);

        assertEquals(900L, result.summary().pv());
        assertEquals(1400L, result.summary().businessEventCount());
        assertEquals(100, result.pages().size());
        assertEquals(100, result.events().size());
        assertTrue(result.pagesTruncated());
        assertTrue(result.eventsTruncated());
        assertTrue(result.rankingNote().contains("full filtered result set"));
    }

    @Test
    void rejectsInvalidWindowAndEventFilterBeforeQueryingClickHouse() {
        ResponseStatusException tooWide = assertThrows(ResponseStatusException.class,
                () -> service.query(project, 169, null, null, null, null));
        ResponseStatusException invalidEvent = assertThrows(ResponseStatusException.class,
                () -> service.query(project, 24, null, null, null, "x' OR 1=1 --"));

        assertEquals(HttpStatus.BAD_REQUEST, tooWide.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, invalidEvent.getStatusCode());
        verifyNoInteractions(clickHouse);
    }

    @Test
    void aggregatesPageviewDwellOverAllViewsAndKeepsPageAndBusinessFiltersSeparate() {
        when(clickHouse.queryForMap(anyString(), any(Object[].class))).thenReturn(summary(3, 2, 2, 4, 300d, 0));
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("page_url", "checkout", "page_views", 3L, "unique_users", 2L, "sessions", 2L,
                        "visible_dwell_ms", 300d, "avg_visible_dwell_ms", 100d)));

        var result = service.query(project, 24, null, null, "checkout", "register_click");

        assertEquals(100d, result.summary().avgVisibleDwellMs());
        assertEquals(100d, result.pages().get(0).avgVisibleDwellMs());
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForMap(sql.capture(), arguments.capture());
        assertTrue(sql.getValue().contains("LEFT JOIN dwell_by_view"));
        assertTrue(sql.getValue().contains("UNION ALL SELECT session_key FROM business_events"));
        assertTrue(Arrays.asList(arguments.getValue()).contains("checkout"));
        assertTrue(Arrays.asList(arguments.getValue()).contains("register_click"));
    }

    @Test
    void rejectsNonFiniteAggregateValuesWithAClientError() {
        when(clickHouse.queryForMap(anyString(), any(Object[].class))).thenReturn(summary(1, 1, 1, 0, 0d, 0));
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("page_url", "checkout", "page_views", 1L, "unique_users", 1L, "sessions", 1L,
                        "visible_dwell_ms", Double.POSITIVE_INFINITY, "avg_visible_dwell_ms", 0d)));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.query(project, 24, null, null, null, null));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, failure.getStatusCode());
    }

    private static Map<String, Object> summary(long pv, long uv, long sessions, long events,
                                               double dwell, long excludedSampled) {
        return Map.of(
                "pv", pv,
                "known_user_uv", uv,
                "sessions", sessions,
                "business_event_count", events,
                "visible_dwell_ms", dwell,
                "excluded_sampled", excludedSampled);
    }

    private static List<Map<String, Object>> rows(
            int count, String firstKey, String... remainingKeys) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Map<String, Object> row = new HashMap<>();
            row.put(firstKey, firstKey + "-" + index);
            for (String key : remainingKeys) {
                row.put(key, key.contains("dwell") ? 1d : 1L);
            }
            rows.add(row);
        }
        return rows;
    }
}
