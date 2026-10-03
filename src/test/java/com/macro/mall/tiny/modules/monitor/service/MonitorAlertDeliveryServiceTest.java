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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MonitorAlertDeliveryServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final MonitorAlertDeliveryMapper deliveryMapper = mock(MonitorAlertDeliveryMapper.class);
    private final MonitorAlertRuleMapper ruleMapper = mock(MonitorAlertRuleMapper.class);
    private final MonitorAlertRecordMapper recordMapper = mock(MonitorAlertRecordMapper.class);
    private final MonitorProjectMapper projectMapper = mock(MonitorProjectMapper.class);
    private final MonitorAlertNotificationRouteService routeService = mock(MonitorAlertNotificationRouteService.class);
    private final MonitorAlertDeliveryService service = new MonitorAlertDeliveryService(
            redis, new ObjectMapper(), deliveryMapper, ruleMapper, recordMapper, projectMapper, routeService);

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
    void persistsSuccessfulWebhookDeliveryAndAddsIdempotencyKey() throws Exception {
        AtomicInteger reads = mockDeliveryReads();
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> retries = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(retries);
        AtomicInteger requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"deliveryId\"")) requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            service.send(rule(server.getAddress().getPort()), alert(), project(), "firing");
        } finally {
            server.stop(0);
        }

        assertEquals(1, requests.get());
        var saved = org.mockito.ArgumentCaptor.forClass(MonitorAlertDeliveryEntity.class);
        verify(deliveryMapper).updateById(saved.capture());
        assertEquals("delivered", saved.getValue().getStatus());
        assertEquals(1, saved.getValue().getAttempts());
        verify(retries, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void persistsFailedWebhookAndSchedulesRetry() throws Exception {
        mockDeliveryReads();
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> retries = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(retries);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            service.send(rule(server.getAddress().getPort()), alert(), project(), "resolved");
        } finally {
            server.stop(0);
        }

        var saved = org.mockito.ArgumentCaptor.forClass(MonitorAlertDeliveryEntity.class);
        verify(deliveryMapper).updateById(saved.capture());
        assertEquals("pending", saved.getValue().getStatus());
        assertEquals(1, saved.getValue().getAttempts());
        assertTrue(saved.getValue().getNextAttemptAt() > System.currentTimeMillis());
        verify(retries).add(eq("monitor:alert:delivery:retry"), anyString(), anyDouble());
        verify(redis).expire(eq("monitor:alert:delivery:retry"), any());
    }

    private AtomicInteger mockDeliveryReads() {
        AtomicInteger reads = new AtomicInteger();
        when(deliveryMapper.selectById(anyString())).thenAnswer(invocation -> {
            int read = reads.getAndIncrement();
            if (read == 0) return null;
            MonitorAlertDeliveryEntity entity = new MonitorAlertDeliveryEntity();
            entity.setId(invocation.getArgument(0));
            entity.setProjectId(7L);
            entity.setRuleId(8L);
            entity.setAlertRecordId(9L);
            entity.setAlertStatus("firing");
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

    private MonitorAlertRule rule(int port) {
        MonitorAlertRule rule = new MonitorAlertRule();
        rule.setId(8L);
        rule.setName("test rule");
        rule.setLevel("error");
        rule.setMetric("error_count");
        rule.setWebhookUrl("http://127.0.0.1:" + port + "/");
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
        project.setProjectKey("test-project");
        return project;
    }
}
