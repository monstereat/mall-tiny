package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorUptimeCheckMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorUptimeHistoryMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorUptimeCheck;
import com.macro.mall.tiny.modules.monitor.model.MonitorUptimeHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitorUptimeServiceTest {

    @Mock private MonitorUptimeCheckMapper checkMapper;
    @Mock private MonitorUptimeHistoryMapper historyMapper;
    @Mock private MonitorProjectAccessService projectAccessService;
    @Mock private MonitorUptimeProbe probe;
    @Mock private ThreadPoolTaskExecutor monitorUptimeExecutor;

    @Test
    void appliesFailureAndRecoveryThresholdsAcrossProbeResults() {
        MonitorUptimeCheck unknown = monitor("unknown", 0, 0);
        MonitorUptimeCheck warning = monitor("warning", 1, 0);
        MonitorUptimeCheck down = monitor("down", 2, 0);
        MonitorUptimeCheck recovering = monitor("down", 2, 1);
        MonitorUptimeCheck recovered = monitor("up", 0, 2);
        when(checkMapper.selectList(any())).thenReturn(
                List.of(unknown), List.of(warning), List.of(down), List.of(recovering), List.of(recovered));
        when(checkMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(probe.check("https://health.example/check", "GET", 200, 1000)).thenReturn(
                result(false, 503, "bad gateway"),
                result(false, 503, "bad gateway"),
                result(true, 200, null),
                result(true, 200, null),
                result(true, 200, null));
        runExecutorInline();

        MonitorUptimeService service = service();
        for (int index = 0; index < 5; index++) service.evaluateDueChecks();

        ArgumentCaptor<UpdateWrapper> updates = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(checkMapper, times(10)).update(isNull(), updates.capture());
        List<UpdateWrapper> stateUpdates = updates.getAllValues().stream()
                .filter(update -> update.getSqlSet().contains("current_status"))
                .toList();
        assertEquals(5, stateUpdates.size());
        assertEquals(List.of("warning", "down", "down", "up", "up"), stateUpdates.stream()
                .map(update -> setValue(update, "current_status")).toList());
        assertEquals(List.of(1, 2, 2, 0, 0), stateUpdates.stream()
                .map(update -> setValue(update, "consecutive_failures")).toList());
        assertEquals(List.of(0, 0, 1, 2, 3), stateUpdates.stream()
                .map(update -> setValue(update, "consecutive_successes")).toList());

        ArgumentCaptor<MonitorUptimeHistory> history = ArgumentCaptor.forClass(MonitorUptimeHistory.class);
        verify(historyMapper, times(5)).insert(history.capture());
        assertEquals(List.of("down", "down", "up", "up", "up"), history.getAllValues().stream()
                .map(MonitorUptimeHistory::getStatus).toList());
    }

    @Test
    void compareAndSetClaimAllowsOnlyOneProbeForTheSameDueMonitor() {
        MonitorUptimeCheck due = monitor("unknown", 0, 0);
        when(checkMapper.selectList(any())).thenReturn(List.of(due), List.of(due));
        AtomicInteger claimCount = new AtomicInteger();
        doAnswer(invocation -> {
            UpdateWrapper<?> update = invocation.getArgument(1);
            if (update.getSqlSet().contains("next_check_at")) return claimCount.getAndIncrement() == 0 ? 1 : 0;
            return 1;
        }).when(checkMapper).update(isNull(), any(UpdateWrapper.class));
        when(probe.check("https://health.example/check", "GET", 200, 1000))
                .thenReturn(result(true, 200, null));
        runExecutorInline();

        MonitorUptimeService service = service();
        service.evaluateDueChecks();
        service.evaluateDueChecks();

        verify(probe, times(1)).check("https://health.example/check", "GET", 200, 1000);
        verify(historyMapper, times(1)).insert(any(MonitorUptimeHistory.class));
        verify(checkMapper, times(3)).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void historyLookupRequiresTheMonitorToBelongToTheRequestedProject() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "monitor-uptime-test"),
                MonitorUptimeCheck.class);
        MonitorProject project = new MonitorProject();
        project.setId(22L);
        when(projectAccessService.requireProject("project-b", false)).thenReturn(project);
        when(checkMapper.selectOne(any())).thenReturn(null);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().history("project-b", 41L, 20));

        assertEquals(404, error.getStatusCode().value());
        ArgumentCaptor<LambdaQueryWrapper<MonitorUptimeCheck>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(checkMapper).selectOne(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("project_id"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(22L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(41L));
        verify(historyMapper, never()).selectList(any());
    }

    private MonitorUptimeService service() {
        return new MonitorUptimeService(checkMapper, historyMapper, projectAccessService, probe, monitorUptimeExecutor);
    }

    private void runExecutorInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(monitorUptimeExecutor).execute(any(Runnable.class));
    }

    private MonitorUptimeCheck monitor(String currentStatus, int failures, int successes) {
        MonitorUptimeCheck monitor = new MonitorUptimeCheck();
        monitor.setId(41L);
        monitor.setProjectId(22L);
        monitor.setSlug("health-check");
        monitor.setName("Health check");
        monitor.setUrl("https://health.example/check");
        monitor.setMethod("GET");
        monitor.setTimeoutMs(1000);
        monitor.setExpectedStatusCode(200);
        monitor.setIntervalSeconds(60);
        monitor.setFailureThreshold(2);
        monitor.setRecoveryThreshold(2);
        monitor.setStatus("active");
        monitor.setCurrentStatus(currentStatus);
        monitor.setConsecutiveFailures(failures);
        monitor.setConsecutiveSuccesses(successes);
        monitor.setNextCheckAt(new Date(System.currentTimeMillis() - 1000));
        return monitor;
    }

    private MonitorUptimeProbe.Result result(boolean successful, Integer responseStatus, String error) {
        return new MonitorUptimeProbe.Result(successful, responseStatus, 17, error);
    }

    private Object setValue(UpdateWrapper<?> update, String column) {
        Matcher matcher = Pattern.compile(Pattern.quote(column) + "=#\\{ew\\.paramNameValuePairs\\.(\\w+)}")
                .matcher(update.getSqlSet());
        assertTrue(matcher.find(), update.getSqlSet());
        Map<String, Object> values = update.getParamNameValuePairs();
        return values.get(matcher.group(1));
    }
}
