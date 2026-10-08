package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorCronMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorCron;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
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
class MonitorCronHealthAlertServiceTest {

    @Mock private MonitorAlertRuleMapper ruleMapper;
    @Mock private MonitorCronMapper cronMapper;
    @Mock private MonitorProjectMapper projectMapper;
    @Mock private MonitorAlertEngine alertEngine;

    @Test
    void publishesUnhealthyMonitorCountThroughTheSharedAlertEngine() {
        MonitorProject project = project();
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(2L), rule(2L)));
        when(cronMapper.selectList(any())).thenReturn(List.of(cron(2L, "warning"), cron(2L, "error"), cron(2L, "ok")));
        when(projectMapper.selectById(2L)).thenReturn(project);

        new MonitorCronHealthAlertService(ruleMapper, cronMapper, projectMapper, alertEngine).evaluateCronHealth();

        ArgumentCaptor<MonitorEventEnvelope> observation = ArgumentCaptor.forClass(MonitorEventEnvelope.class);
        verify(alertEngine).evaluate(eq(project), observation.capture(), eq("cron-health"));
        assertEquals("cron_unhealthy_count", observation.getValue().getData().get("name"));
        assertEquals(2L, observation.getValue().getData().get("value"));
    }

    @Test
    void publishesZeroAfterAllMonitorsRecoverSoTheAlertCanResolve() {
        MonitorProject project = project();
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(2L)));
        when(cronMapper.selectList(any())).thenReturn(List.of(cron(2L, "ok")));
        when(projectMapper.selectById(2L)).thenReturn(project);

        new MonitorCronHealthAlertService(ruleMapper, cronMapper, projectMapper, alertEngine).evaluateCronHealth();

        ArgumentCaptor<MonitorEventEnvelope> observation = ArgumentCaptor.forClass(MonitorEventEnvelope.class);
        verify(alertEngine).evaluate(eq(project), observation.capture(), eq("cron-health"));
        assertEquals(0L, observation.getValue().getData().get("value"));
    }

    private MonitorProject project() {
        MonitorProject project = new MonitorProject();
        project.setId(2L);
        project.setProjectKey("demo");
        project.setStatus(1);
        return project;
    }

    private MonitorAlertRule rule(Long projectId) {
        MonitorAlertRule rule = new MonitorAlertRule();
        rule.setProjectId(projectId);
        rule.setMetric(MonitorCronHealthAlertService.METRIC);
        rule.setEnabled(1);
        return rule;
    }

    private MonitorCron cron(Long projectId, String healthStatus) {
        MonitorCron cron = new MonitorCron();
        cron.setProjectId(projectId);
        cron.setStatus("active");
        cron.setHealthStatus(healthStatus);
        return cron;
    }
}
