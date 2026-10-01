package com.macro.mall.tiny.modules.monitor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.AlertRuleRequest;
import com.macro.mall.tiny.modules.monitor.dto.SourceMapResolvedPosition;
import com.macro.mall.tiny.modules.monitor.model.*;
import com.macro.mall.tiny.modules.monitor.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/monitor/admin")
@RequiredArgsConstructor
public class MonitorAdminController {

    private final MonitorAdminService adminService;
    private final MonitorQueryService queryService;
    private final MonitorReplayService replayService;
    private final MonitorSourceMapService sourceMapService;
    private final ObjectMapper objectMapper;

    @GetMapping("/projects")
    public CommonResult<List<MonitorProject>> projects() {
        return CommonResult.success(queryService.projects());
    }

    @GetMapping("/{projectKey}/dashboard")
    public CommonResult<Map<String, Object>> dashboard(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "24") int hours) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.dashboard(project, hours));
    }

    @GetMapping("/{projectKey}/issues")
    public CommonResult<Object> issues(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String status) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.issues(project.getId(), pageNum, pageSize, status));
    }

    @GetMapping("/{projectKey}/issues/{issueId}")
    public CommonResult<Map<String, Object>> issue(
            @PathVariable String projectKey,
            @PathVariable Long issueId,
            @RequestParam(defaultValue = "20") int eventLimit) {
        MonitorProject project = adminService.requireProject(projectKey);
        MonitorIssue issue = queryService.issue(project.getId(), issueId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issue", issue);
        result.put("events", issue == null ? List.of() : queryService.issueEvents(project, issue, eventLimit));
        return CommonResult.success(result);
    }

    @GetMapping("/{projectKey}/releases")
    public CommonResult<List<MonitorRelease>> releases(@PathVariable String projectKey) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.releases(project.getId()));
    }

    @GetMapping("/{projectKey}/replays")
    public CommonResult<List<MonitorReplay>> replays(
            @PathVariable String projectKey,
            @RequestParam(required = false) String sessionId) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.replays(project.getId(), sessionId));
    }

    @GetMapping("/{projectKey}/replays/{replayId}")
    public CommonResult<JsonNode> replay(
            @PathVariable String projectKey,
            @PathVariable Long replayId) throws IOException {
        MonitorProject project = adminService.requireProject(projectKey);
        MonitorReplay replay = queryService.replay(project.getId(), replayId);
        if (replay == null) {
            return CommonResult.success(null);
        }
        return CommonResult.success(objectMapper.readTree(replayService.load(replay)));
    }

    @GetMapping("/{projectKey}/alerts/rules")
    public CommonResult<List<MonitorAlertRule>> alertRules(@PathVariable String projectKey) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.alertRules(project.getId()));
    }

    @PostMapping("/{projectKey}/alerts/rules")
    public CommonResult<MonitorAlertRule> createAlertRule(
            @PathVariable String projectKey,
            @Valid @RequestBody AlertRuleRequest request) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(adminService.createRule(project, request));
    }

    @PutMapping("/{projectKey}/alerts/rules/{ruleId}")
    public CommonResult<MonitorAlertRule> updateAlertRule(
            @PathVariable String projectKey,
            @PathVariable Long ruleId,
            @Valid @RequestBody AlertRuleRequest request) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(adminService.updateRule(project, ruleId, request));
    }

    @GetMapping("/{projectKey}/alerts/records")
    public CommonResult<List<MonitorAlertRecord>> alertRecords(@PathVariable String projectKey) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.alertRecords(project.getId()));
    }

    @GetMapping("/{projectKey}/sourcemap/resolve")
    public CommonResult<SourceMapResolvedPosition> resolveSourceMap(
            @PathVariable String projectKey,
            @RequestParam String version,
            @RequestParam(defaultValue = "production") String environment,
            @RequestParam String bundleFile,
            @RequestParam int line,
            @RequestParam int column) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(
                sourceMapService.resolveForAdmin(
                        project, version, environment, bundleFile, line, column
                ).orElse(null)
        );
    }
}
