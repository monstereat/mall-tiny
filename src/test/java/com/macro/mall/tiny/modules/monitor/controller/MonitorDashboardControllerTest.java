package com.macro.mall.tiny.modules.monitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDashboardRequest;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MonitorDashboardControllerTest {
    private final MonitorProjectAccessService accessService = mock(MonitorProjectAccessService.class);
    private final MonitorDashboardService dashboardService = mock(MonitorDashboardService.class);
    private final MonitorAdminController controller = new MonitorAdminController(
            mock(MonitorAdminService.class), mock(MonitorQueryService.class), mock(MonitorReplayService.class),
            mock(MonitorSourceMapService.class), new ObjectMapper(), mock(MonitorProjectService.class),
            accessService, mock(MonitorAlertSilenceService.class), mock(MonitorAlertDeliveryService.class),
            mock(MonitorLogQueryService.class), mock(MonitorReleaseHealthService.class),
            mock(MonitorSavedExploreQueryService.class), dashboardService,
            mock(MonitorAlertNotificationRouteService.class));

    @Test
    void createUpdateAndDeleteRequireProjectWriteAccess() {
        when(accessService.requireProject("store-a", true))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        MonitorDashboardRequest request = new MonitorDashboardRequest();

        assertThrows(ResponseStatusException.class, () -> controller.createDashboard("store-a", request));
        assertThrows(ResponseStatusException.class, () -> controller.updateDashboard("store-a", 7L, request));
        assertThrows(ResponseStatusException.class, () -> controller.deleteDashboard("store-a", 7L));

        verify(accessService, times(3)).requireProject("store-a", true);
        verifyNoInteractions(dashboardService);
    }

    @Test
    void viewerCanReadButReceivesCanModifyFalse() {
        MonitorAdminService adminService = mock(MonitorAdminService.class);
        MonitorDashboardService dashboardService = mock(MonitorDashboardService.class);
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(accessService.requireProject("store-a", true))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        MonitorAdminController readController = new MonitorAdminController(
                adminService, mock(MonitorQueryService.class), mock(MonitorReplayService.class),
                mock(MonitorSourceMapService.class), new ObjectMapper(), mock(MonitorProjectService.class),
                accessService, mock(MonitorAlertSilenceService.class), mock(MonitorAlertDeliveryService.class),
                mock(MonitorLogQueryService.class), mock(MonitorReleaseHealthService.class),
                mock(MonitorSavedExploreQueryService.class), dashboardService,
                mock(MonitorAlertNotificationRouteService.class));

        readController.dashboards("store-a");

        verify(dashboardService).list(project, false);
    }
}
