package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.AlertRuleRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueBulkStatusRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssue;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MonitorAdminService {

    private final MonitorProjectAccessService projectAccessService;
    private final MonitorAlertRuleMapper alertRuleMapper;
    private final MonitorIssueMapper issueMapper;
    private final MonitorAlertNotificationRouteService notificationRouteService;
    private final MonitorIssueActivityService issueActivityService;
    private final MonitorAlertWebhookClient webhookClient;

    public MonitorProject requireProject(String projectKey) {
        return projectAccessService.requireProject(projectKey, false);
    }

    public MonitorProject requireProject(String projectKey, boolean write) {
        return projectAccessService.requireProject(projectKey, write);
    }

    public MonitorProject requireProjectOwner(String projectKey) {
        return projectAccessService.requireProjectOwner(projectKey);
    }

    @Transactional
    public MonitorAlertRule createRule(MonitorProject project, AlertRuleRequest request) {
        notificationRouteService.validateRoute(project, request.getNotificationRouteId());
        MonitorAlertRule rule = copy(new MonitorAlertRule(), project, request);
        alertRuleMapper.insert(rule);
        return rule;
    }

    @Transactional
    public MonitorAlertRule updateRule(MonitorProject project, Long id, AlertRuleRequest request) {
        notificationRouteService.validateRoute(project, request.getNotificationRouteId());
        MonitorAlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null || !project.getId().equals(rule.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert rule not found");
        }
        copy(rule, project, request);
        alertRuleMapper.updateById(rule);
        return rule;
    }

    @Transactional
    public MonitorIssue updateIssueStatus(MonitorProject project, Long issueId, String status) {
        if (!java.util.Set.of("unresolved", "resolved", "ignored").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid issue status");
        }
        MonitorIssue issue = issueMapper.selectById(issueId);
        if (issue == null || !project.getId().equals(issue.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "issue not found");
        }
        String previousStatus = issue.getStatus();
        issue.setStatus(status);
        issue.setRegressedAt(null);
        issue.setResolvedAt("resolved".equals(status) ? new java.util.Date() : null);
        issueMapper.update(null, Wrappers.<MonitorIssue>update()
                .eq("project_id", project.getId())
                .eq("id", issueId)
                .set("status", status)
                .set("regressed_at", null)
                .set("resolved_at", issue.getResolvedAt()));
        issueActivityService.recordStatusChange(project, issueId, previousStatus, status);
        return issue;
    }

    @Transactional
    public int updateIssuesStatus(MonitorProject project, MonitorIssueBulkStatusRequest request) {
        String status = request.getStatus();
        if (!java.util.Set.of("unresolved", "resolved", "ignored").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid issue status");
        }
        List<MonitorIssue> issues = issueMapper.selectList(Wrappers.<MonitorIssue>query()
                .eq("project_id", project.getId())
                .in("id", request.getIssueIds()));
        int updated = issueMapper.update(null, Wrappers.<MonitorIssue>update()
                .eq("project_id", project.getId())
                .in("id", request.getIssueIds())
                .set("status", status)
                .set("regressed_at", null)
                .set("resolved_at", "resolved".equals(status) ? new java.util.Date() : null));
        for (MonitorIssue issue : issues) {
            issueActivityService.recordStatusChange(project, issue.getId(), issue.getStatus(), status);
        }
        return updated;
    }

    private MonitorAlertRule copy(MonitorAlertRule rule, MonitorProject project, AlertRuleRequest request) {
        rule.setProjectId(project.getId());
        rule.setName(request.getName());
        rule.setMetric(request.getMetric());
        rule.setOperator(request.getOperator());
        rule.setThresholdValue(request.getThresholdValue());
        rule.setWindowSeconds(request.getWindowSeconds());
        rule.setDurationSeconds(request.getDurationSeconds());
        rule.setCooldownSeconds(request.getCooldownSeconds());
        rule.setLevel(request.getLevel());
        rule.setWebhookUrl(request.getNotificationRouteId() == null
                ? webhookClient.normalizeOptionalUrl(request.getWebhookUrl()) : null);
        rule.setNotificationRouteId(request.getNotificationRouteId());
        rule.setEnabled(request.getEnabled());
        return rule;
    }
}
