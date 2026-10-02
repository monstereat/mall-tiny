package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRecordMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertDelivery;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRecord;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MonitorAlertDeliveryService {

    private static final Duration RETENTION = Duration.ofDays(90);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_ATTEMPTS = 6;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MonitorAlertRuleMapper ruleMapper;
    private final MonitorAlertRecordMapper recordMapper;
    private final MonitorProjectMapper projectMapper;

    public void send(MonitorAlertRule rule, MonitorAlertRecord alert,
                     MonitorProject project, String alertStatus) {
        long now = System.currentTimeMillis();
        MonitorAlertDelivery delivery = new MonitorAlertDelivery(
                UUID.randomUUID().toString(), project.getId(), rule.getId(), alert.getId(),
                alertStatus, StringUtils.hasText(rule.getWebhookUrl()) ? "pending" : "skipped",
                0, 0, now, now, null);
        save(delivery);
        if ("pending".equals(delivery.status())) {
            attempt(delivery, rule, alert, project);
        }
    }

    public List<MonitorAlertDelivery> list(Long projectId) {
        ZSetOperations<String, String> index = redisTemplate.opsForZSet();
        String key = indexKey(projectId);
        index.removeRangeByScore(key, 0, System.currentTimeMillis() - RETENTION.toMillis());
        Set<String> ids = index.reverseRangeByScore(
                key, 0, Double.POSITIVE_INFINITY, 0, 200);
        List<MonitorAlertDelivery> result = new ArrayList<>();
        if (ids == null) {
            return result;
        }
        for (String id : ids) {
            MonitorAlertDelivery delivery = get(id);
            if (delivery != null) {
                result.add(delivery);
            }
        }
        return result;
    }

    @Scheduled(fixedDelayString = "${monitor.alert.delivery-retry-interval-ms:5000}")
    public void retryPending() {
        long now = System.currentTimeMillis();
        ZSetOperations<String, String> retries = redisTemplate.opsForZSet();
        Set<String> ids = retries.rangeByScore(retryKey(), 0, now, 0, 100);
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            Long claimed = retries.remove(retryKey(), id);
            if (!Long.valueOf(1).equals(claimed)) {
                continue;
            }
            MonitorAlertDelivery delivery = get(id);
            if (delivery == null || !"pending".equals(delivery.status())) {
                continue;
            }
            MonitorAlertRule rule = ruleMapper.selectById(delivery.ruleId());
            MonitorAlertRecord alert = recordMapper.selectById(delivery.alertRecordId());
            MonitorProject project = projectMapper.selectById(delivery.projectId());
            if (rule == null || alert == null || project == null) {
                save(copy(delivery, "failed", delivery.attempts(), 0,
                        "delivery target no longer exists"));
                continue;
            }
            attempt(delivery, rule, alert, project);
        }
    }

    private void attempt(MonitorAlertDelivery delivery, MonitorAlertRule rule,
                         MonitorAlertRecord alert, MonitorProject project) {
        int attempts = delivery.attempts() + 1;
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
            requestFactory.setReadTimeout(READ_TIMEOUT);
            RestClient.builder().requestFactory(requestFactory).build().post()
                    .uri(rule.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "project", project.getProjectKey(),
                            "rule", rule.getName(),
                            "level", rule.getLevel(),
                            "metric", rule.getMetric(),
                            "value", alert.getMetricValue(),
                            "threshold", alert.getThresholdValue(),
                            "status", delivery.alertStatus(),
                            "message", alert.getMessage()
                    ))
                    .retrieve()
                    .toBodilessEntity();
            save(copy(delivery, "delivered", attempts, 0, null));
        } catch (RuntimeException ignored) {
            if (attempts >= MAX_ATTEMPTS) {
                save(copy(delivery, "failed", attempts, 0, "webhook delivery failed"));
                return;
            }
            long delaySeconds = Math.min(300, 5L << Math.min(attempts - 1, 5));
            long nextAttemptAt = System.currentTimeMillis() + delaySeconds * 1000L;
            save(copy(delivery, "pending", attempts, nextAttemptAt, "webhook delivery failed"));
            redisTemplate.opsForZSet().add(retryKey(), delivery.id(), nextAttemptAt);
            redisTemplate.expire(retryKey(), RETENTION);
        }
    }

    private MonitorAlertDelivery copy(MonitorAlertDelivery source, String status,
                                      int attempts, long nextAttemptAt, String lastError) {
        return new MonitorAlertDelivery(source.id(), source.projectId(), source.ruleId(),
                source.alertRecordId(), source.alertStatus(), status, attempts, nextAttemptAt,
                source.createdAt(), System.currentTimeMillis(), lastError);
    }

    private void save(MonitorAlertDelivery delivery) {
        try {
            redisTemplate.opsForValue().set(recordKey(delivery.id()),
                    objectMapper.writeValueAsString(delivery), RETENTION);
            String index = indexKey(delivery.projectId());
            redisTemplate.opsForZSet().add(index, delivery.id(), delivery.createdAt());
            redisTemplate.expire(index, RETENTION);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("alert delivery serialization failed", e);
        }
    }

    private MonitorAlertDelivery get(String id) {
        String value = redisTemplate.opsForValue().get(recordKey(id));
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, MonitorAlertDelivery.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("alert delivery deserialization failed", e);
        }
    }

    private String recordKey(String id) {
        return "monitor:alert:delivery:record:" + id;
    }

    private String indexKey(Long projectId) {
        return "monitor:alert:delivery:index:" + projectId;
    }

    private String retryKey() {
        return "monitor:alert:delivery:retry";
    }
}
