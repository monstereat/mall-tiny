package com.macro.mall.tiny.modules.monitor.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonitorReleaseHealthServiceTest {

    private final JdbcTemplate clickHouse = mock(JdbcTemplate.class);
    private final MonitorReleaseHealthService service = new MonitorReleaseHealthService(clickHouse);

    @Test
    void summarizesStartedSessionsAndUsersWithUnhandledErrors() {
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("uniqExactIf(user_health.user_id,user_health.crashed=1)")) {
                return List.of(Map.of(
                        "release", "web@1.2.3",
                        "environment", "production",
                        "users", 7L,
                        "crashed_users", 1L));
            }
            return List.of(Map.of(
                    "release", "web@1.2.3",
                    "environment", "production",
                    "sessions", 10L,
                    "crashed_sessions", 2L,
                    "unhandled_errors", 3L));
        });

        var result = service.list("demo-web", 168);

        assertEquals(1, result.size());
        assertEquals("web@1.2.3", result.get(0).release());
        assertEquals("production", result.get(0).environment());
        assertEquals(10, result.get(0).sessions());
        assertEquals(2, result.get(0).crashedSessions());
        assertEquals(80, result.get(0).crashFreeSessionsRate());
        assertEquals(7, result.get(0).users());
        assertEquals(1, result.get(0).crashedUsers());
        assertEquals(85.71, result.get(0).crashFreeUsersRate());
        assertEquals(3, result.get(0).unhandledErrors());
    }

    @Test
    void queriesOnlySessionStartsAndUnhandledErrorsInTheRequestedProject() {
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.list("demo-web", 24);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(clickHouse, org.mockito.Mockito.times(2))
                .queryForList(captor.capture(), any(Object[].class));
        List<String> queries = captor.getAllValues();
        assertTrue(queries.get(0).contains("JSONExtractString(payload,'data','action')='start'"));
        assertTrue(queries.get(0).contains("JSONExtractBool(payload,'data','unhandled')"));
        assertTrue(queries.get(1).contains("INNER JOIN started_sessions"));
        assertTrue(queries.get(1).contains("uniqExactIf(user_health.user_id,user_health.crashed=1)"));
    }
}
