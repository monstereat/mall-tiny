package com.macro.mall.tiny.modules.monitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.service.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonitorAdminAlertRuleViewTest {

    private static final String WEBHOOK_URL = "https://hooks.example.test/alert?token=private";

    private final MonitorAdminService adminService = mock(MonitorAdminService.class);
    private final MonitorQueryService queryService = mock(MonitorQueryService.class);
    private final MonitorProjectAccessService projectAccessService = mock(MonitorProjectAccessService.class);
    private final MonitorAdminController controller = new MonitorAdminController(
            adminService,
            queryService,
            mock(MonitorReplayService.class),
            mock(MonitorSourceMapService.class),
            mock(ObjectMapper.class),
            mock(MonitorProjectService.class),
            projectAccessService,
            mock(MonitorAlertSilenceService.class),
            mock(MonitorAlertDeliveryService.class),
            mock(MonitorLogQueryService.class),
            mock(MonitorReleaseHealthService.class),
            mock(MonitorSavedExploreQueryService.class),
            mock(MonitorDashboardService.class),
            mock(MonitorAlertNotificationRouteService.class)
    );

    @Test
    void hidesLegacyWebhookUrlFromReadOnlyProjectMemberWithoutChangingRule() {
        MonitorProject project = project();
        MonitorAlertRule rule = rule();
        when(adminService.requireProject("demo")).thenReturn(project);
        when(projectAccessService.canWriteProject(project)).thenReturn(false);
        when(queryService.alertRules(project.getId())).thenReturn(List.of(rule));

        var result = controller.alertRules("demo").getData().get(0);

        assertNull(result.webhookUrl());
        assertEquals(WEBHOOK_URL, rule.getWebhookUrl());
    }

    @Test
    void retainsLegacyWebhookUrlForProjectWriterWithoutChangingRule() {
        MonitorProject project = project();
        MonitorAlertRule rule = rule();
        when(adminService.requireProject("demo")).thenReturn(project);
        when(projectAccessService.canWriteProject(project)).thenReturn(true);
        when(queryService.alertRules(project.getId())).thenReturn(List.of(rule));

        var result = controller.alertRules("demo").getData().get(0);

        assertEquals(WEBHOOK_URL, result.webhookUrl());
        assertEquals(WEBHOOK_URL, rule.getWebhookUrl());
    }

    private MonitorProject project() {
        MonitorProject project = new MonitorProject();
        project.setId(17L);
        return project;
    }

    private MonitorAlertRule rule() {
        MonitorAlertRule rule = new MonitorAlertRule();
        rule.setId(29L);
        rule.setProjectId(17L);
        rule.setName("Legacy alert");
        rule.setWebhookUrl(WEBHOOK_URL);
        return rule;
    }
}
