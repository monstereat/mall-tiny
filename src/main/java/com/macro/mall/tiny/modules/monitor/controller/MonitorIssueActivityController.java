package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueActivityPage;
import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueActivityView;
import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueCommentRequest;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.service.MonitorAdminService;
import com.macro.mall.tiny.modules.monitor.service.MonitorIssueActivityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/monitor/admin/{projectKey}/issues/{issueId}/activities")
@RequiredArgsConstructor
public class MonitorIssueActivityController {

    private final MonitorAdminService adminService;
    private final MonitorIssueActivityService activityService;

    @GetMapping
    public CommonResult<MonitorIssueActivityPage> list(
            @PathVariable String projectKey,
            @PathVariable Long issueId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "50") int limit) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(activityService.list(project, issueId, beforeId, limit));
    }

    @PostMapping("/comments")
    public CommonResult<MonitorIssueActivityView> addComment(
            @PathVariable String projectKey,
            @PathVariable Long issueId,
            @Valid @RequestBody MonitorIssueCommentRequest request) {
        MonitorProject project = adminService.requireProject(projectKey, true);
        return CommonResult.success(activityService.addComment(project, issueId, request.getComment()));
    }
}
