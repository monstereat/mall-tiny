package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertDeliveryMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRecordMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRecord;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertDeliveryEntity;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MonitorAlertDeliveryServiceTest {
    private static final String DINGTALK_ROBOT = "https://oapi.dingtalk.com/robot/send?access_token=test-token";
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final MonitorAlertDeliveryMapper deliveryMapper = mock(MonitorAlertDeliveryMapper.class);
    private final MonitorAlertRuleMapper ruleMapper = mock(MonitorAlertRuleMapper.class);
    private final MonitorAlertRecordMapper recordMapper = mock(MonitorAlertRecordMapper.class);
    private final MonitorProjectMapper projectMapper = mock(MonitorProjectMapper.class);
    private final MonitorAlertNotificationRouteService routeService = mock(MonitorAlertNotificationRouteService.class);
    private final MonitorAlertWebhookClient webhookClient = mock(MonitorAlertWebhookClient.class);
    private final MonitorAlertDeliveryService service = new MonitorAlertDeliveryService(
            redis, new ObjectMapper(), deliveryMapper, ruleMapper, recordMapper, projectMapper, routeService,
            new MonitorAlertNotificationSender(webhookClient, DINGTALK_ROBOT, "SECtest-secret", "7"));

    @Test
    void listsDeliveryHistoryFromDurableDatabaseRecords() {
        MonitorAlertDeliveryEntity entity = new MonitorAlertDeliveryEntity();
        entity.setId("delivery-1");
        entity.setProjectId(7L);
        entity.setRuleId(8L);
        entity.setAlertRecordId(9L);
        entity.setAlertStatus("firing");
        entity.setStatus("delivered");
        entity.setAttempts(1);
        entity.setNextAttemptAt(0L);
        entity.setCreatedAt(System.currentTimeMillis());
        entity.setUpdatedAt(entity.getCreatedAt());
        when(deliveryMapper.selectList(any())).thenReturn(List.of(entity));
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> index = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(index);
        when(index.reverseRangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Set.of());

        var result = service.list(7L);

        assertEquals(1, result.size());
        assertEquals("delivery-1", result.get(0).id());
        assertEquals("delivered", result.get(0).status());
    }

    @Test
    void recoversDueRetriesFromDatabaseIntoRedisSchedule() {
        MonitorAlertDeliveryEntity due = new MonitorAlertDeliveryEntity();
        due.setId("delivery-2");
        due.setStatus("pending");
        due.setNextAttemptAt(System.currentTimeMillis() - 1_000);
        when(deliveryMapper.selectRecoverable(anyLong())).thenReturn(List.of(due));
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> retries = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(retries);

        service.recoverPendingRetries();

        verify(retries).add(eq("monitor:alert:delivery:retry"), eq("delivery-2"), anyDouble());
        verify(redis).expire(eq("monitor:alert:delivery:retry"), any());
    }

    @Test
    void persistsSuccessfulWebhookDeliveryAndAddsIdempotencyKey() {
        mockDeliveryReads();
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> retries = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(retries);
        service.send(rule(), alert(), project(), "firing");

        var payload = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(webhookClient).post(eq("https://hooks.example.test/alert"), payload.capture());
        assertTrue(payload.getValue().containsKey("deliveryId"));
        var saved = org.mockito.ArgumentCaptor.forClass(MonitorAlertDeliveryEntity.class);
        verify(deliveryMapper).updateById(saved.capture());
        assertEquals("delivered", saved.getValue().getStatus());
        assertEquals(1, saved.getValue().getAttempts());
        verify(retries, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void persistsFailedWebhookAndSchedulesRetry() {
        mockDeliveryReads();
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> retries = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(retries);
        doThrow(new IllegalStateException("webhook returned HTTP 503"))
                .when(webhookClient).post(anyString(), any());
        service.send(rule(), alert(), project(), "resolved");

        var saved = org.mockito.ArgumentCaptor.forClass(MonitorAlertDeliveryEntity.class);
        verify(deliveryMapper).updateById(saved.capture());
        assertEquals("pending", saved.getValue().getStatus());
        assertEquals(1, saved.getValue().getAttempts());
        assertTrue(saved.getValue().getNextAttemptAt() > System.currentTimeMillis());
        verify(retries).add(eq("monitor:alert:delivery:retry"), anyString(), anyDouble());
        verify(redis).expire(eq("monitor:alert:delivery:retry"), any());
    }

    @Test
    void retriesWebhookRedirectFailuresWithoutFollowingThem() {
        mockDeliveryReads();
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> retries = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(retries);
        doThrow(new IllegalStateException("webhook redirect rejected"))
                .when(webhookClient).post(anyString(), any());
        service.send(rule(), alert(), project(), "firing");

        var saved = org.mockito.ArgumentCaptor.forClass(MonitorAlertDeliveryEntity.class);
        verify(deliveryMapper).updateById(saved.capture());
        assertEquals("pending", saved.getValue().getStatus());
        assertEquals(1, saved.getValue().getAttempts());
        assertTrue(saved.getValue().getNextAttemptAt() > System.currentTimeMillis());
        verify(retries).add(eq("monitor:alert:delivery:retry"), anyString(), anyDouble());
    }

    @Test
    void permanentlyRejectsPrivateWebhookTargetsWithoutRetrying() {
        mockDeliveryReads();
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> retries = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(retries);
        doThrow(new MonitorAlertWebhookClient.UnsafeTargetException("non-public target"))
                .when(webhookClient).post(anyString(), any());

        service.send(rule(), alert(), project(), "firing");

        var saved = org.mockito.ArgumentCaptor.forClass(MonitorAlertDeliveryEntity.class);
        verify(deliveryMapper).updateById(saved.capture());
        assertEquals("failed", saved.getValue().getStatus());
        assertEquals(1, saved.getValue().getAttempts());
        assertEquals("webhook destination rejected", saved.getValue().getLastError());
        verify(retries, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void dingTalkBusinessErrorRemainsPendingAndSchedulesRetry() throws Exception {
        mockDeliveryReads();
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> retries = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(retries);
        MonitorAlertRule robotRule = rule();
        robotRule.setWebhookUrl(DINGTALK_ROBOT);
        when(webhookClient.postForJson(anyString(), any()))
                .thenReturn(new ObjectMapper().readTree("{\"errcode\":310000,\"errmsg\":\"secret\"}"));

        service.send(robotRule, alert(), project(), "firing");

        var saved = org.mockito.ArgumentCaptor.forClass(MonitorAlertDeliveryEntity.class);
        verify(deliveryMapper).updateById(saved.capture());
        assertEquals("pending", saved.getValue().getStatus());
        assertEquals("webhook delivery failed", saved.getValue().getLastError());
        verify(retries).add(eq("monitor:alert:delivery:retry"), anyString(), anyDouble());
    }

    @Test
    void dingTalkSuccessMarksResolvedNotificationDelivered() throws Exception {
        mockDeliveryReads("resolved");
        MonitorAlertRule robotRule = rule();
        robotRule.setWebhookUrl(DINGTALK_ROBOT);
        when(webhookClient.postForJson(anyString(), any()))
                .thenReturn(new ObjectMapper().readTree("{\"errcode\":0}"));

        service.send(robotRule, alert(), project(), "resolved");

        var saved = org.mockito.ArgumentCaptor.forClass(MonitorAlertDeliveryEntity.class);
        verify(deliveryMapper).updateById(saved.capture());
        assertEquals("delivered", saved.getValue().getStatus());
        assertEquals("resolved", saved.getValue().getAlertStatus());
        verify(webhookClient, never()).post(anyString(), any());
    }

    @Test
    void permanentlyRejectsLegacyConfiguredRobotUrlForAnotherTenantWithoutRetry() {
        mockDeliveryReads();
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> retries = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(retries);
        MonitorAlertRule robotRule = rule();
        robotRule.setWebhookUrl(DINGTALK_ROBOT);
        MonitorProject otherTenantProject = project();
        otherTenantProject.setTenantId(8L);

        service.send(robotRule, alert(), otherTenantProject, "firing");

        var saved = org.mockito.ArgumentCaptor.forClass(MonitorAlertDeliveryEntity.class);
        verify(deliveryMapper).updateById(saved.capture());
        assertEquals("failed", saved.getValue().getStatus());
        assertEquals(1, saved.getValue().getAttempts());
        assertEquals("webhook destination rejected", saved.getValue().getLastError());
        verify(retries, never()).add(anyString(), anyString(), anyDouble());
        verify(webhookClient, never()).postForJson(anyString(), any());
        verify(webhookClient, never()).post(anyString(), any());
    }

    private AtomicInteger mockDeliveryReads() {
        return mockDeliveryReads("firing");
    }

    private AtomicInteger mockDeliveryReads(String alertStatus) {
        AtomicInteger reads = new AtomicInteger();
        when(deliveryMapper.selectById(anyString())).thenAnswer(invocation -> {
            int read = reads.getAndIncrement();
            if (read == 0) return null;
            MonitorAlertDeliveryEntity entity = new MonitorAlertDeliveryEntity();
            entity.setId(invocation.getArgument(0));
            entity.setProjectId(7L);
            entity.setRuleId(8L);
            entity.setAlertRecordId(9L);
            entity.setAlertStatus(alertStatus);
            entity.setStatus(read == 2 ? "sending" : "pending");
            entity.setAttempts(0);
            entity.setNextAttemptAt(0L);
            entity.setCreatedAt(System.currentTimeMillis());
            entity.setUpdatedAt(entity.getCreatedAt());
            return entity;
        });
        when(deliveryMapper.claim(anyString(), anyLong(), anyLong())).thenReturn(1);
        return reads;
    }

    private MonitorAlertRule rule() {
        MonitorAlertRule rule = new MonitorAlertRule();
        rule.setId(8L);
        rule.setName("test rule");
        rule.setLevel("error");
        rule.setMetric("error_count");
        rule.setWebhookUrl("https://hooks.example.test/alert");
        return rule;
    }

    private MonitorAlertRecord alert() {
        MonitorAlertRecord alert = new MonitorAlertRecord();
        alert.setId(9L);
        alert.setMetricValue(java.math.BigDecimal.ONE);
        alert.setThresholdValue(java.math.BigDecimal.ONE);
        alert.setMessage("test alert");
        return alert;
    }

    private MonitorProject project() {
        MonitorProject project = new MonitorProject();
        project.setId(7L);
        project.setTenantId(7L);
        project.setProjectKey("test-project");
        return project;
    }
}
