package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.macro.mall.tiny.modules.monitor.dto.AlertRuleRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueBulkStatusRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssue;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MonitorAdminServiceIssueBulkTest {

    private final MonitorProjectAccessService projectAccessService = mock(MonitorProjectAccessService.class);
    private final MonitorAlertRuleMapper alertRuleMapper = mock(MonitorAlertRuleMapper.class);
    private final MonitorIssueMapper issueMapper = mock(MonitorIssueMapper.class);
    private final MonitorAlertNotificationRouteService notificationRouteService = mock(MonitorAlertNotificationRouteService.class);
    private final MonitorIssueActivityService issueActivityService = mock(MonitorIssueActivityService.class);
    private final MonitorAdminService service = new MonitorAdminService(
            projectAccessService, alertRuleMapper, issueMapper, notificationRouteService, issueActivityService,
            new MonitorAlertWebhookClient(new com.fasterxml.jackson.databind.ObjectMapper()));

    @Test
    void bulkStatusUpdateIsScopedToProjectAndSelectedIssues() {
        MonitorProject project = new MonitorProject();
        project.setId(42L);
        MonitorIssueBulkStatusRequest request = new MonitorIssueBulkStatusRequest();
        request.setIssueIds(List.of(10L, 11L));
        request.setStatus("resolved");
        MonitorIssue issue = new MonitorIssue();
        issue.setId(10L);
        issue.setStatus("unresolved");
        when(issueMapper.selectList(any())).thenReturn(List.of(issue));
        when(issueMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(2);

        int updated = service.updateIssuesStatus(project, request);

        assertEquals(2, updated);
        ArgumentCaptor<UpdateWrapper<MonitorIssue>> updateCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(issueMapper).update(isNull(), updateCaptor.capture());
        assertTrue(updateCaptor.getValue().getSqlSet().contains("regressed_at"));
        assertTrue(updateCaptor.getValue().getSqlSet().contains("resolved_at"));
        verify(issueActivityService).recordStatusChange(project, 10L, "unresolved", "resolved");
    }

    @Test
    void rejectsUnsupportedBulkStatusBeforeDatabaseUpdate() {
        MonitorProject project = new MonitorProject();
        project.setId(42L);
        MonitorIssueBulkStatusRequest request = new MonitorIssueBulkStatusRequest();
        request.setIssueIds(List.of(10L));
        request.setStatus("assigned");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.updateIssuesStatus(project, request)
        );

        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(issueMapper);
    }

    @Test
    void rejectsPrivateLegacyWebhookUrlBeforePersistingAlertRule() {
        MonitorProject project = new MonitorProject();
        project.setId(42L);
        AlertRuleRequest request = new AlertRuleRequest();
        request.setWebhookUrl("http://127.0.0.1/metadata");

        assertThrows(MonitorAlertWebhookClient.UnsafeTargetException.class,
                () -> service.createRule(project, request));

        verifyNoInteractions(alertRuleMapper);
    }

    @Test
    void singleStatusUpdateTracksResolutionTimeAndClearsRegressionTime() {
        MonitorProject project = new MonitorProject();
        project.setId(42L);
        MonitorIssue issue = new MonitorIssue();
        issue.setId(10L);
        issue.setProjectId(42L);
        issue.setStatus("unresolved");
        issue.setRegressedAt(new java.util.Date(1_000));
        when(issueMapper.selectById(10L)).thenReturn(issue);
        when(issueMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        MonitorIssue updated = service.updateIssueStatus(project, 10L, "resolved");

        assertNotNull(updated.getResolvedAt());
        assertNull(updated.getRegressedAt());
        ArgumentCaptor<UpdateWrapper<MonitorIssue>> updateCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(issueMapper).update(isNull(), updateCaptor.capture());
        assertTrue(updateCaptor.getValue().getSqlSet().contains("resolved_at"));
        assertTrue(updateCaptor.getValue().getSqlSet().contains("regressed_at"));
        verify(issueActivityService).recordStatusChange(project, 10L, "unresolved", "resolved");
    }

    @Test
    void upsertOnlyReopensForEventsAfterTheRecordedResolutionTime() throws Exception {
        String sql = String.join(" ", MonitorIssueMapper.class
                .getMethod("upsert", Long.class, String.class, String.class, Long.class, java.util.Date.class, String.class)
                .getAnnotation(Insert.class).value());

        assertTrue(sql.contains("VALUES(last_seen) > resolved_at"));
        assertTrue(sql.contains("regressed_at = IF(status = 'resolved'"));
        assertTrue(sql.contains("status = IF(status = 'resolved'"));
    }
}
