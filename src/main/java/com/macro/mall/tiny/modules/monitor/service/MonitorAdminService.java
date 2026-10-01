package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.AlertRuleRequest;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MonitorAdminService {

    private final MonitorProjectService projectService;
    private final MonitorAlertRuleMapper alertRuleMapper;

    public MonitorProject requireProject(String projectKey) {
        MonitorProject project = projectService.getActiveProject(projectKey);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor project not found");
        }
        return project;
    }

    @Transactional
    public MonitorAlertRule createRule(MonitorProject project, AlertRuleRequest request) {
        MonitorAlertRule rule = copy(new MonitorAlertRule(), project, request);
        alertRuleMapper.insert(rule);
        return rule;
    }

    @Transactional
    public MonitorAlertRule updateRule(MonitorProject project, Long id, AlertRuleRequest request) {
        MonitorAlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null || !project.getId().equals(rule.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert rule not found");
        }
        copy(rule, project, request);
        alertRuleMapper.updateById(rule);
        return rule;
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
        rule.setWebhookUrl(request.getWebhookUrl());
        rule.setEnabled(request.getEnabled());
        return rule;
    }
}
