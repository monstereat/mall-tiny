package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MonitorLogQueryServiceTest {

    private final JdbcTemplate clickHouse = mock(JdbcTemplate.class);
    private final MonitorLogQueryService service = new MonitorLogQueryService(clickHouse, "http://localhost:3100");

    @Test
    void rejectsInvalidTraceIdBeforeQueryingStorage() {
        MonitorProject project = project("store-a");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.search(project, "not-a-trace-id", 24, 200)
        );

        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(clickHouse);
    }

    @Test
    void doesNotQueryLokiWhenTraceDoesNotBelongToProject() {
        when(clickHouse.queryForObject(any(String.class), eq(Long.class), any(Object[].class))).thenReturn(0L);
        MonitorProject project = project("store-a");

        var result = service.search(project, "0123456789abcdef0123456789abcdef", 24, 200);

        assertEquals(0, result.entries().size());
        verify(clickHouse).queryForObject(
                contains("project_id=? AND trace_id=?"),
                eq(Long.class),
                any(Object[].class)
        );
    }

    private MonitorProject project(String key) {
        MonitorProject project = new MonitorProject();
        project.setProjectKey(key);
        return project;
    }
}
