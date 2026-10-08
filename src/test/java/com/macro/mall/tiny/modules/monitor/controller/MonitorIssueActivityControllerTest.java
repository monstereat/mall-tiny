package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueActivityPage;
import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueActivityView;
import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueCommentRequest;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.service.MonitorAdminService;
import com.macro.mall.tiny.modules.monitor.service.MonitorIssueActivityService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MonitorIssueActivityControllerTest {

    private final MonitorAdminService adminService = mock(MonitorAdminService.class);
    private final MonitorIssueActivityService activityService = mock(MonitorIssueActivityService.class);
    private final MonitorIssueActivityController controller = new MonitorIssueActivityController(adminService, activityService);

    @Test
    void activityReadUsesAuthorizedProjectAndCursor() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a")).thenReturn(project);
        when(activityService.list(project, 5L, 30L, 25)).thenReturn(new MonitorIssueActivityPage(List.of(), false, null));

        controller.list("store-a", 5L, 30L, 25);

        verify(activityService).list(project, 5L, 30L, 25);
    }

    @Test
    void commentWriteIsDeniedBeforeServiceWhenProjectIsReadOnly() {
        when(adminService.requireProject("store-a", true))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        MonitorIssueCommentRequest request = new MonitorIssueCommentRequest();
        request.setComment("comment");

        assertThrows(ResponseStatusException.class, () -> controller.addComment("store-a", 5L, request));

        verifyNoInteractions(activityService);
    }

    @Test
    void authorizedCommentUsesCurrentProjectAndIssue() {
        MonitorProject project = new MonitorProject();
        when(adminService.requireProject("store-a", true)).thenReturn(project);
        when(activityService.addComment(project, 5L, "comment"))
                .thenReturn(new MonitorIssueActivityView(1L, "comment", 8L, "alice", "comment", null, null, null));
        MonitorIssueCommentRequest request = new MonitorIssueCommentRequest();
        request.setComment("comment");

        controller.addComment("store-a", 5L, request);

        verify(activityService).addComment(project, 5L, "comment");
    }
}
