package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertDeliveryMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRecordMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertDelivery;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertDeliveryEntity;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRecord;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 6;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MonitorAlertDeliveryMapper deliveryMapper;
    private final MonitorAlertRuleMapper ruleMapper;
    private final MonitorAlertRecordMapper recordMapper;
    private final MonitorProjectMapper projectMapper;
    private final MonitorAlertNotificationRouteService notificationRouteService;

    public void send(MonitorAlertRule rule, MonitorAlertRecord alert,
                     MonitorProject project, String alertStatus) {
        long now = System.currentTimeMillis();
        String destination = destination(rule, project);
        MonitorAlertDelivery delivery = new MonitorAlertDelivery(
                UUID.randomUUID().toString(), project.getId(), rule.getId(), alert.getId(),
                alertStatus, StringUtils.hasText(destination) ? "pending" : "skipped",
                0, 0, now, now, null);
        save(delivery);
        if ("pending".equals(delivery.status())) {
            if (claim(delivery.id(), now)) {
                attempt(get(delivery.id()), rule, alert, project);
            }
        }
    }

    public List<MonitorAlertDelivery> list(Long projectId) {
        long cutoff = System.currentTimeMillis() - RETENTION.toMillis();
        Map<String, MonitorAlertDelivery> deliveries = new LinkedHashMap<>();
        deliveryMapper.selectList(Wrappers.<MonitorAlertDeliveryEntity>lambdaQuery()
                        .eq(MonitorAlertDeliveryEntity::getProjectId, projectId)
                        .ge(MonitorAlertDeliveryEntity::getCreatedAt, cutoff)
                        .orderByDesc(MonitorAlertDeliveryEntity::getCreatedAt)
                        .last("LIMIT 200"))
                .stream().map(this::toDelivery).forEach(delivery -> deliveries.put(delivery.id(), delivery));
        try {
            ZSetOperations<String, String> index = redisTemplate.opsForZSet();
            String legacyIndex = indexKey(projectId);
            index.removeRangeByScore(legacyIndex, 0, cutoff);
            Set<String> legacyIds = index.reverseRangeByScore(legacyIndex, cutoff, Double.POSITIVE_INFINITY, 0, 200);
            if (legacyIds != null) {
                for (String id : legacyIds) {
                    if (!deliveries.containsKey(id)) {
                        MonitorAlertDelivery legacy = get(id);
                        if (legacy != null) deliveries.put(id, legacy);
                    }
                }
            }
        } catch (DataAccessException ignored) {
            // MySQL is authoritative; keep durable history available during Redis outages.
        }
        return deliveries.values().stream()
                .filter(delivery -> delivery.createdAt() >= cutoff)
                .sorted(Comparator.comparingLong(MonitorAlertDelivery::createdAt).reversed())
                .limit(200).toList();
    }

    @Scheduled(fixedDelayString = "${monitor.alert.delivery-recovery-interval-ms:10000}")
    public void recoverPendingRetries() {
        long now = System.currentTimeMillis();
        List<MonitorAlertDeliveryEntity> due = deliveryMapper.selectRecoverable(now);
        if (due.isEmpty()) return;
        ZSetOperations<String, String> retries = redisTemplate.opsForZSet();
        for (MonitorAlertDeliveryEntity delivery : due) {
            long dueAt = "sending".equals(delivery.getStatus()) ? now : delivery.getNextAttemptAt();
            retries.add(retryKey(), delivery.getId(), (double) dueAt);
        }
        redisTemplate.expire(retryKey(), RETENTION);
    }

    @Scheduled(fixedDelayString = "${monitor.alert.delivery-cleanup-interval-ms:3600000}")
    public void purgeExpiredDeliveries() {
        deliveryMapper.deleteExpired(System.currentTimeMillis() - RETENTION.toMillis());
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
            if (!claim(id, now)) {
                continue;
            }
            MonitorAlertDelivery delivery = get(id);
            if (delivery == null) continue;
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
            String destination = destination(rule, project);
            if (!StringUtils.hasText(destination)) {
                save(copy(delivery, "skipped", attempts, 0, null));
                return;
            }
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
            requestFactory.setReadTimeout(READ_TIMEOUT);
            RestClient.builder().requestFactory(requestFactory).build().post()
                    .uri(destination)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "deliveryId", delivery.id(),
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

    private String destination(MonitorAlertRule rule, MonitorProject project) {
        if (rule.getNotificationRouteId() != null) {
            return notificationRouteService.resolveUrl(project, rule.getNotificationRouteId());
        }
        return rule.getWebhookUrl();
    }

    private MonitorAlertDelivery copy(MonitorAlertDelivery source, String status,
                                      int attempts, long nextAttemptAt, String lastError) {
        return new MonitorAlertDelivery(source.id(), source.projectId(), source.ruleId(),
                source.alertRecordId(), source.alertStatus(), status, attempts, nextAttemptAt,
                source.createdAt(), System.currentTimeMillis(), lastError);
    }

    private void save(MonitorAlertDelivery delivery) {
        MonitorAlertDeliveryEntity entity = toEntity(delivery);
        if (deliveryMapper.selectById(delivery.id()) == null) {
            deliveryMapper.insert(entity);
        } else {
            deliveryMapper.updateById(entity);
        }
    }

    private MonitorAlertDelivery get(String id) {
        MonitorAlertDeliveryEntity delivery = deliveryMapper.selectById(id);
        if (delivery != null) return toDelivery(delivery);
        String legacy;
        try {
            legacy = redisTemplate.opsForValue().get(recordKey(id));
        } catch (DataAccessException ignored) {
            return null;
        }
        if (legacy == null) return null;
        try {
            return objectMapper.readValue(legacy, MonitorAlertDelivery.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("legacy alert delivery deserialization failed", e);
        }
    }

    private MonitorAlertDeliveryEntity toEntity(MonitorAlertDelivery delivery) {
        MonitorAlertDeliveryEntity entity = new MonitorAlertDeliveryEntity();
        entity.setId(delivery.id());
        entity.setProjectId(delivery.projectId());
        entity.setRuleId(delivery.ruleId());
        entity.setAlertRecordId(delivery.alertRecordId());
        entity.setAlertStatus(delivery.alertStatus());
        entity.setStatus(delivery.status());
        entity.setAttempts(delivery.attempts());
        entity.setNextAttemptAt(delivery.nextAttemptAt());
        entity.setClaimUntil(0L);
        entity.setCreatedAt(delivery.createdAt());
        entity.setUpdatedAt(delivery.updatedAt());
        entity.setLastError(delivery.lastError());
        return entity;
    }

    private MonitorAlertDelivery toDelivery(MonitorAlertDeliveryEntity entity) {
        return new MonitorAlertDelivery(entity.getId(), entity.getProjectId(), entity.getRuleId(),
                entity.getAlertRecordId(), entity.getAlertStatus(), entity.getStatus(),
                entity.getAttempts() == null ? 0 : entity.getAttempts(),
                entity.getNextAttemptAt() == null ? 0 : entity.getNextAttemptAt(),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getLastError());
    }

    private String retryKey() {
        return "monitor:alert:delivery:retry";
    }

    private boolean claim(String id, long now) {
        if (deliveryMapper.selectById(id) == null) {
            MonitorAlertDelivery legacy = get(id);
            if (legacy == null) return false;
            save(legacy);
        }
        return deliveryMapper.claim(id, now, now + CLAIM_LEASE.toMillis()) == 1;
    }

    private String recordKey(String id) {
        return "monitor:alert:delivery:record:" + id;
    }

    private String indexKey(Long projectId) {
        return "monitor:alert:delivery:index:" + projectId;
    }
}
