package com.macro.mall.tiny.modules.monitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class MonitorTraceControllerTest {
    private final MonitorAdminService adminService = mock(MonitorAdminService.class);
    private final MonitorQueryService queryService = mock(MonitorQueryService.class);
    private final MonitorAdminController controller = new MonitorAdminController(
            adminService, queryService, mock(MonitorReplayService.class), mock(MonitorSourceMapService.class),
            new ObjectMapper(), mock(MonitorProjectService.class), mock(MonitorProjectAccessService.class),
            mock(MonitorAlertSilenceService.class), mock(MonitorAlertDeliveryService.class),
            mock(MonitorLogQueryService.class), mock(MonitorReleaseHealthService.class),
            mock(MonitorSavedExploreQueryService.class), mock(MonitorDashboardService.class),
            mock(MonitorAlertNotificationRouteService.class));

    @Test
    void traceSpansRequireReadAccessToTheRequestedProjectBeforeQuerying() {
        when(adminService.requireProject("store-b"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThrows(ResponseStatusException.class, () -> controller.traceSpans("store-b", "trace-from-store-a"));

        verify(adminService).requireProject("store-b");
        verifyNoInteractions(queryService);
    }
}
