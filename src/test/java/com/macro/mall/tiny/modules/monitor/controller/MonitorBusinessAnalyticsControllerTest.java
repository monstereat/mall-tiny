package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.modules.monitor.dto.MonitorBusinessAnalyticsResult;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.service.MonitorAdminService;
import com.macro.mall.tiny.modules.monitor.service.MonitorBusinessAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MonitorBusinessAnalyticsControllerTest {

    private final MonitorAdminService adminService = mock(MonitorAdminService.class);
    private final MonitorBusinessAnalyticsService analyticsService = mock(MonitorBusinessAnalyticsService.class);
    private final MonitorBusinessAnalyticsController controller =
            new MonitorBusinessAnalyticsController(adminService, analyticsService);

    @Test
    void requiresProjectAccessBeforeQueryingAnalytics() {
        MonitorProject project = new MonitorProject();
        MonitorBusinessAnalyticsResult result = new MonitorBusinessAnalyticsResult(
                new MonitorBusinessAnalyticsResult.Summary(1, 1, 1, 2, 100, 100, 0),
                List.of(), List.of(), List.of(), false, false, "bounded rankings");
        when(adminService.requireProject("project-a")).thenReturn(project);
        when(analyticsService.query(project, 24, "production", "web-42", "checkout", "register_click"))
                .thenReturn(result);

        var response = controller.query("project-a", 24, "production", "web-42", "checkout", "register_click");

        assertSame(result, response.getData());
        verify(adminService).requireProject("project-a");
        verify(analyticsService).query(project, 24, "production", "web-42", "checkout", "register_click");
    }

    @Test
    void doesNotQueryAnalyticsWhenProjectAccessIsDenied() {
        when(adminService.requireProject("project-a")).thenThrow(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "project access denied"));

        assertThrows(ResponseStatusException.class,
                () -> controller.query("project-a", 24, null, null, null, null));

        verify(adminService).requireProject("project-a");
        verifyNoInteractions(analyticsService);
    }
}
