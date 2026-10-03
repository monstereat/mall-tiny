package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorUptimeCheckMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorUptimeCheck;
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
public class MonitorUptimeAlertService {

    public static final String UNHEALTHY_METRIC = "uptime_unhealthy_count";
    public static final String LATENCY_METRIC = "uptime_max_latency_ms";
    private static final List<String> METRICS = List.of(UNHEALTHY_METRIC, LATENCY_METRIC);

    private final MonitorAlertRuleMapper ruleMapper;
    private final MonitorUptimeCheckMapper uptimeMapper;
    private final MonitorProjectMapper projectMapper;
    private final MonitorAlertEngine alertEngine;

    @Scheduled(fixedDelayString = "${monitor.uptime.alert-evaluation-interval-ms:15000}")
    public void evaluateUptimeHealth() {
        List<MonitorAlertRule> rules = ruleMapper.selectList(Wrappers.<MonitorAlertRule>lambdaQuery()
                .eq(MonitorAlertRule::getEnabled, 1)
                .in(MonitorAlertRule::getMetric, METRICS));
        if (rules.isEmpty()) return;

        Set<Long> projectIds = new HashSet<>();
        rules.forEach(rule -> projectIds.add(rule.getProjectId()));
        Map<Long, Long> unhealthyByProject = new HashMap<>();
        Map<Long, Integer> latencyByProject = new HashMap<>();
        List<MonitorUptimeCheck> monitors = uptimeMapper.selectList(Wrappers.<MonitorUptimeCheck>lambdaQuery()
                .in(MonitorUptimeCheck::getProjectId, projectIds)
                .eq(MonitorUptimeCheck::getStatus, "active"));
        for (MonitorUptimeCheck monitor : monitors) {
            if ("warning".equals(monitor.getCurrentStatus()) || "down".equals(monitor.getCurrentStatus())) {
                unhealthyByProject.merge(monitor.getProjectId(), 1L, Long::sum);
            }
            if (monitor.getLastDurationMs() != null) {
                latencyByProject.merge(monitor.getProjectId(), Math.toIntExact(Math.min(Integer.MAX_VALUE, monitor.getLastDurationMs())), Math::max);
            }
        }

        for (Long projectId : projectIds) {
            MonitorProject project = projectMapper.selectById(projectId);
            if (project == null || !Integer.valueOf(1).equals(project.getStatus())) continue;
            publish(project, UNHEALTHY_METRIC, unhealthyByProject.getOrDefault(projectId, 0L));
            publish(project, LATENCY_METRIC, latencyByProject.getOrDefault(projectId, 0));
        }
    }

    private void publish(MonitorProject project, String metric, Number value) {
        MonitorEventEnvelope observation = new MonitorEventEnvelope();
        observation.setEventId(UUID.randomUUID().toString());
        observation.setProjectId(project.getProjectKey());
        observation.setEventType(MonitorEventType.METRIC);
        observation.setTimestamp(System.currentTimeMillis());
        observation.getData().put("name", metric);
        observation.getData().put("value", value);
        alertEngine.evaluate(project, observation, "uptime:" + metric);
    }
}
