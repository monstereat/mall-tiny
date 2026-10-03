package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueAiAnalysis;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.service.MonitorAdminService;
import com.macro.mall.tiny.modules.monitor.service.MonitorIssueAiAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/monitor/admin/{projectKey}/issues/{issueId}/ai-analysis")
@RequiredArgsConstructor
public class MonitorIssueAiAnalysisController {

    private final MonitorAdminService adminService;
    private final MonitorIssueAiAnalysisService analysisService;

    @PostMapping
    public CommonResult<MonitorIssueAiAnalysis> analyze(
            @PathVariable String projectKey,
            @PathVariable Long issueId) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(analysisService.analyze(project, issueId));
    }
}
