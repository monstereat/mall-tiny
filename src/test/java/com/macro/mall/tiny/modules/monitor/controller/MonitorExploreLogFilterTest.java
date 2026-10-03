package com.macro.mall.tiny.modules.monitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonitorExploreLogFilterTest {

    private final MonitorAdminService adminService = mock(MonitorAdminService.class);
    private final MonitorAdminController controller = new MonitorAdminController(
            adminService,
            mock(MonitorQueryService.class),
            mock(MonitorReplayService.class),
            mock(MonitorSourceMapService.class),
            mock(ObjectMapper.class),
            mock(MonitorProjectService.class),
            mock(MonitorProjectAccessService.class),
            mock(MonitorAlertSilenceService.class),
            mock(MonitorAlertDeliveryService.class),
            mock(MonitorLogQueryService.class),
            mock(MonitorSavedExploreQueryService.class),
            mock(MonitorAlertNotificationRouteService.class)
    );

    @Test
    void rejectsUserFilterForLogsInsteadOfIgnoringIt() {
        when(adminService.requireProject("store-a")).thenReturn(new MonitorProject());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.explore("store-a", 24, "logs", null, null, null, null,
                        "user-1", null, null, 100, 0));

        assertEquals(400, exception.getStatusCode().value());
    }

    @Test
    void rejectsGroupedTraceFiltersForLogsUntilMultipleTraceSearchIsSupported() {
        when(adminService.requireProject("store-a")).thenReturn(new MonitorProject());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.explore("store-a", 24, "logs", null, null, null,
                        "(trace:abc OR trace:def)", null, null, null, 100, 0));

        assertEquals(400, exception.getStatusCode().value());
    }
}
