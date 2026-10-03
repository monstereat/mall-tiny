package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorCronMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorCron;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MonitorCronHealthAlertService {

    public static final String METRIC = "cron_unhealthy_count";

    private final MonitorAlertRuleMapper ruleMapper;
    private final MonitorCronMapper cronMapper;
    private final MonitorProjectMapper projectMapper;
    private final MonitorAlertEngine alertEngine;

    @Scheduled(fixedDelayString = "${monitor.cron.alert-evaluation-interval-ms:15000}")
    public void evaluateCronHealth() {
        List<MonitorAlertRule> rules = ruleMapper.selectList(Wrappers.<MonitorAlertRule>lambdaQuery()
                .eq(MonitorAlertRule::getEnabled, 1)
                .eq(MonitorAlertRule::getMetric, METRIC));
        if (rules.isEmpty()) return;

        Set<Long> projectIds = new HashSet<>();
        rules.forEach(rule -> projectIds.add(rule.getProjectId()));
        Map<Long, Long> unhealthyByProject = new HashMap<>();
        List<MonitorCron> monitors = cronMapper.selectList(Wrappers.<MonitorCron>lambdaQuery()
                .in(MonitorCron::getProjectId, projectIds)
                .eq(MonitorCron::getStatus, "active"));
        for (MonitorCron monitor : monitors) {
            if ("warning".equals(monitor.getHealthStatus()) || "error".equals(monitor.getHealthStatus())) {
                unhealthyByProject.merge(monitor.getProjectId(), 1L, Long::sum);
            }
        }

        for (Long projectId : projectIds) {
            MonitorProject project = projectMapper.selectById(projectId);
            if (project == null || !Integer.valueOf(1).equals(project.getStatus())) continue;
            MonitorEventEnvelope observation = new MonitorEventEnvelope();
            observation.setEventId(UUID.randomUUID().toString());
            observation.setProjectId(project.getProjectKey());
            observation.setEventType(MonitorEventType.METRIC);
            observation.setTimestamp(System.currentTimeMillis());
            observation.getData().put("name", METRIC);
            observation.getData().put("value", unhealthyByProject.getOrDefault(projectId, 0L));
            alertEngine.evaluate(project, observation, "cron-health");
        }
    }
}
