package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRecordMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRecord;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssue;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonitorIssueServiceNotificationTest {
    private final MonitorIssueMapper issueMapper = mock(MonitorIssueMapper.class);
    private final MonitorAlertRuleMapper ruleMapper = mock(MonitorAlertRuleMapper.class);
    private final MonitorAlertRecordMapper alertRecordMapper = mock(MonitorAlertRecordMapper.class);
    private final MonitorAlertDeliveryService deliveryService = mock(MonitorAlertDeliveryService.class);
    private final MonitorAlertSilenceService silenceService = mock(MonitorAlertSilenceService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    @SuppressWarnings("unchecked")
    private final HyperLogLogOperations<String, String> hll = mock(HyperLogLogOperations.class);
    private final MonitorIssueService service = new MonitorIssueService(
            issueMapper, ruleMapper, alertRecordMapper, deliveryService, silenceService, redis);

    MonitorIssueServiceNotificationTest() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHyperLogLog()).thenReturn(hll);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(hll.size(anyString())).thenReturn(1L);
        when(silenceService.isSilenced(anyLong(), anyLong(), anyString())).thenReturn(false);
    }

    @Test
    void firstIssueCreatesOneFiringOutboxNotificationForEnabledNewIssueRules() {
        MonitorProject project = project();
        MonitorEventEnvelope event = event(2_000L);
        MonitorAlertRule rule = rule("new_issue");
        when(issueMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule));

        service.aggregate(project, event, "fingerprint-1");

        verify(issueMapper).upsert(eq(7L), eq("fingerprint-1"), eq("checkout failed"), eq(1L), any(Date.class), eq("web-1"));
        var record = org.mockito.ArgumentCaptor.forClass(MonitorAlertRecord.class);
        verify(alertRecordMapper).insert(record.capture());
        assertEquals("new_issue", record.getValue().getMetric());
        assertEquals("resolved", record.getValue().getStatus());
        assertEquals("New issue: checkout failed", record.getValue().getMessage());
        verify(deliveryService).send(rule, record.getValue(), project, "firing");
    }

    @Test
    void resolvedIssueRegressionUsesOnlyRegressionRulesAndRepeatedUnresolvedEventsDoNotNotify() {
        MonitorProject project = project();
        MonitorIssue resolved = new MonitorIssue();
        resolved.setStatus("resolved");
        resolved.setResolvedAt(new Date(1_000L));
        MonitorAlertRule regressionRule = rule("issue_regression");
        when(issueMapper.selectOne(any(Wrapper.class))).thenReturn(resolved);
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(regressionRule));

        service.aggregate(project, event(2_000L), "fingerprint-1");

        verify(deliveryService).send(any(), any(), eq(project), eq("firing"));
        reset(deliveryService, alertRecordMapper, issueMapper, ruleMapper, values);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(issueMapper.selectOne(any(Wrapper.class))).thenReturn(new MonitorIssue());

        service.aggregate(project, event(3_000L), "fingerprint-1");

        verifyNoInteractions(alertRecordMapper, deliveryService);
        verify(issueMapper).upsert(eq(7L), eq("fingerprint-1"), eq("checkout failed"), eq(1L), any(Date.class), eq("web-1"));
    }

    @Test
    void releasesIssueLockAndEventDedupKeyWhenAggregationFails() {
        when(issueMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(issueMapper.upsert(anyLong(), anyString(), anyString(), anyLong(), any(Date.class), anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class,
                () -> service.aggregate(project(), event(2_000L), "fingerprint-1"));

        verify(redis).delete("monitor:issue:aggregated:event-2000");
        verify(redis).execute(any(RedisScript.class), eq(List.of("monitor:issue:transition:7:fingerprint-1")), any());
        verifyNoInteractions(alertRecordMapper, deliveryService);
    }

    @Test
    void retriesFirstIssueNotificationWhenDeliveryCreationFailedAfterIssueUpsert() {
        MonitorProject project = project();
        MonitorEventEnvelope event = event(2_000L);
        MonitorAlertRule rule = rule("new_issue");
        MonitorIssue persistedIssue = new MonitorIssue();
        persistedIssue.setStatus("unresolved");
        persistedIssue.setFirstSeen(new Date(event.getTimestamp()));
        MonitorAlertRecord persistedRecord = notificationRecord(41L, event, "new_issue");
        when(issueMapper.selectOne(any(Wrapper.class))).thenReturn(null, persistedIssue);
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule));
        when(alertRecordMapper.selectOne(any(Wrapper.class))).thenReturn(null, persistedRecord);
        doThrow(new IllegalStateException("outbox unavailable")).doNothing()
                .when(deliveryService).send(any(), any(), any(), anyString());

        assertThrows(IllegalStateException.class,
                () -> service.aggregate(project, event, "fingerprint-1"));
        service.aggregate(project, event, "fingerprint-1");

        verify(alertRecordMapper, times(1)).insert(any(MonitorAlertRecord.class));
        verify(deliveryService, times(2)).send(eq(rule), any(MonitorAlertRecord.class), eq(project), eq("firing"));
        verify(deliveryService).hasDelivery(41L);
    }

    @Test
    void retriesRegressionNotificationAfterIssueWasAlreadyReopened() {
        MonitorProject project = project();
        MonitorEventEnvelope event = event(2_000L);
        MonitorAlertRule rule = rule("issue_regression");
        MonitorIssue resolvedIssue = new MonitorIssue();
        resolvedIssue.setStatus("resolved");
        resolvedIssue.setResolvedAt(new Date(1_000L));
        MonitorIssue regressedIssue = new MonitorIssue();
        regressedIssue.setStatus("unresolved");
        regressedIssue.setRegressedAt(new Date(event.getTimestamp()));
        MonitorAlertRecord persistedRecord = notificationRecord(42L, event, "issue_regression");
        when(issueMapper.selectOne(any(Wrapper.class))).thenReturn(resolvedIssue, regressedIssue);
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule));
        when(alertRecordMapper.selectOne(any(Wrapper.class))).thenReturn(null, persistedRecord);
        doThrow(new IllegalStateException("outbox unavailable")).doNothing()
                .when(deliveryService).send(any(), any(), any(), anyString());

        assertThrows(IllegalStateException.class,
                () -> service.aggregate(project, event, "fingerprint-1"));
        service.aggregate(project, event, "fingerprint-1");

        verify(alertRecordMapper, times(1)).insert(any(MonitorAlertRecord.class));
        verify(deliveryService, times(2)).send(eq(rule), any(MonitorAlertRecord.class), eq(project), eq("firing"));
        verify(deliveryService).hasDelivery(42L);
    }

    @Test
    void expiredOwnerCannotDeleteLockThatWasReacquiredByAnotherOwner() {
        AtomicReference<String> currentOwner = new AtomicReference<>("owner-b");
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenAnswer(invocation -> {
            String requestedOwner = invocation.getArgument(2);
            return currentOwner.compareAndSet(requestedOwner, null) ? 1L : 0L;
        });

        service.releaseTransitionLock("monitor:issue:transition:7:fingerprint-1", "owner-a");

        assertEquals("owner-b", currentOwner.get());
        var script = org.mockito.ArgumentCaptor.forClass(RedisScript.class);
        verify(redis).execute(script.capture(), eq(List.of("monitor:issue:transition:7:fingerprint-1")), eq("owner-a"));
        assertEquals(true, script.getValue().getScriptAsString().contains("redis.call('get', KEYS[1]) == ARGV[1]"));
    }

    private MonitorProject project() {
        MonitorProject project = new MonitorProject();
        project.setId(7L);
        return project;
    }

    private MonitorEventEnvelope event(long timestamp) {
        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventId("event-" + timestamp);
        event.setTimestamp(timestamp);
        event.setRelease("web-1");
        event.setData(Map.of("message", "checkout failed"));
        return event;
    }

    private MonitorAlertRule rule(String metric) {
        MonitorAlertRule rule = new MonitorAlertRule();
        rule.setId(11L);
        rule.setProjectId(7L);
        rule.setMetric(metric);
        rule.setLevel("warning");
        rule.setEnabled(1);
        return rule;
    }

    private MonitorAlertRecord notificationRecord(long id, MonitorEventEnvelope event, String metric) {
        MonitorAlertRecord record = new MonitorAlertRecord();
        record.setId(id);
        record.setProjectId(7L);
        record.setRuleId(11L);
        record.setMetric(metric);
        record.setFingerprint("fingerprint-1");
        record.setTriggeredAt(new Date(event.getTimestamp()));
        record.setMessage("Issue notification");
        return record;
    }
}
