package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorUptimeCheckMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorUptimeCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitorUptimeAlertServiceTest {

    @Mock private MonitorAlertRuleMapper ruleMapper;
    @Mock private MonitorUptimeCheckMapper uptimeMapper;
    @Mock private MonitorProjectMapper projectMapper;
    @Mock private MonitorAlertEngine alertEngine;

    @Test
    void publishesProjectFailureCountAndMaximumLatencyToSharedAlertEngine() {
        MonitorProject project = project();
        MonitorAlertRule unhealthyRule = rule(MonitorUptimeAlertService.UNHEALTHY_METRIC);
        MonitorAlertRule latencyRule = rule(MonitorUptimeAlertService.LATENCY_METRIC);
        MonitorUptimeCheck failed = monitor("down", 4200L);
        MonitorUptimeCheck healthy = monitor("up", 1800L);
        when(ruleMapper.selectList(any())).thenReturn(List.of(unhealthyRule, latencyRule));
        when(uptimeMapper.selectList(any())).thenReturn(List.of(failed, healthy));
        when(projectMapper.selectById(2L)).thenReturn(project);

        new MonitorUptimeAlertService(ruleMapper, uptimeMapper, projectMapper, alertEngine).evaluateUptimeHealth();

        ArgumentCaptor<MonitorEventEnvelope> observations = ArgumentCaptor.forClass(MonitorEventEnvelope.class);
        org.mockito.Mockito.verify(alertEngine, org.mockito.Mockito.times(2))
                .evaluate(eq(project), observations.capture(), any(String.class));
        assertEquals(2, observations.getAllValues().size());
        assertEquals(1L, observations.getAllValues().stream()
                .filter(event -> MonitorUptimeAlertService.UNHEALTHY_METRIC.equals(event.getData().get("name")))
                .findFirst().orElseThrow().getData().get("value"));
        assertEquals(4200, observations.getAllValues().stream()
                .filter(event -> MonitorUptimeAlertService.LATENCY_METRIC.equals(event.getData().get("name")))
                .findFirst().orElseThrow().getData().get("value"));
    }

    private MonitorProject project() {
        MonitorProject project = new MonitorProject();
        project.setId(2L);
        project.setProjectKey("demo");
        project.setStatus(1);
        return project;
    }

    private MonitorAlertRule rule(String metric) {
        MonitorAlertRule rule = new MonitorAlertRule();
        rule.setProjectId(2L);
        rule.setMetric(metric);
        rule.setEnabled(1);
        return rule;
    }

    private MonitorUptimeCheck monitor(String currentStatus, Long durationMs) {
        MonitorUptimeCheck monitor = new MonitorUptimeCheck();
        monitor.setProjectId(2L);
        monitor.setStatus("active");
        monitor.setCurrentStatus(currentStatus);
        monitor.setLastDurationMs(durationMs);
        return monitor;
    }
}
