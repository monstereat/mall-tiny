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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 负责把告警消息发到外部 Webhook，并记录发送结果。
 * MySQL 保存每条消息的状态；Redis 有序集合只用来安排“什么时候重试”。服务重启后可从 MySQL 找回未完成的消息。
 */
@Service
@RequiredArgsConstructor
public class MonitorAlertDeliveryService {

    private static final Duration RETENTION = Duration.ofDays(90);
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 6;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MonitorAlertDeliveryMapper deliveryMapper;
    private final MonitorAlertRuleMapper ruleMapper;
    private final MonitorAlertRecordMapper recordMapper;
    private final MonitorProjectMapper projectMapper;
    private final MonitorAlertNotificationRouteService notificationRouteService;
    private final MonitorAlertNotificationSender notificationSender;

    public void send(MonitorAlertRule rule, MonitorAlertRecord alert,
                     MonitorProject project, String alertStatus) {
        long now = System.currentTimeMillis();
        String destination = destination(rule, project);
        MonitorAlertDelivery delivery = new MonitorAlertDelivery(
                UUID.randomUUID().toString(), project.getId(), rule.getId(), alert.getId(),
                alertStatus, StringUtils.hasText(destination) ? "pending" : "skipped",
                0, 0, now, now, null);
        // 先把“待发送/跳过”状态写进 MySQL，再请求外部地址；即使进程中途退出，仍能找到这条消息继续处理。
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
        // 以 MySQL 中的记录为准；同时读取旧版本只写在 Redis 里的记录，兼容升级前的数据。
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

    public boolean hasDelivery(Long alertRecordId) {
        Long count = deliveryMapper.selectCount(Wrappers.<MonitorAlertDeliveryEntity>lambdaQuery()
                .eq(MonitorAlertDeliveryEntity::getAlertRecordId, alertRecordId));
        return count != null && count > 0;
    }

    @Scheduled(fixedDelayString = "${monitor.alert.delivery-recovery-interval-ms:10000}")
    public void recoverPendingRetries() {
        // 定期从数据库找回待发送、或“发送中”占用时间已到的消息，补上重启或队列记录丢失留下的空档。
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
        // 发送历史保留 90 天；数据库历史和 Redis 中的重试时间表分别清理。
        deliveryMapper.deleteExpired(System.currentTimeMillis() - RETENTION.toMillis());
    }

    @Scheduled(fixedDelayString = "${monitor.alert.delivery-retry-interval-ms:5000}")
    public void retryPending() {
        // Redis 有序集合按重试时间排序；每轮最多取 100 条，避免后台定时任务同时发出太多网络请求。
        long now = System.currentTimeMillis();
        ZSetOperations<String, String> retries = redisTemplate.opsForZSet();
        Set<String> ids = retries.rangeByScore(retryKey(), 0, now, 0, 100);
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            // 先从 Redis 取到期消息，再在数据库申请一个有时限的处理权，避免多个服务实例重复发送同一条。
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
        // attempts 是发送次数；本次发送后的成功/失败状态会写回同一条记录。
        int attempts = delivery.attempts() + 1;
        try {
            String destination = destination(rule, project);
            if (!StringUtils.hasText(destination)) {
                save(copy(delivery, "skipped", attempts, 0, null));
                return;
            }
            notificationSender.send(project.getTenantId(), destination, Map.of(
                    "deliveryId", delivery.id(),
                    "project", project.getProjectKey(),
                    "rule", rule.getName(),
                    "level", rule.getLevel(),
                    "metric", rule.getMetric(),
                    "value", alert.getMetricValue(),
                    "threshold", alert.getThresholdValue(),
                    "status", delivery.alertStatus(),
                    "message", alert.getMessage()
            ));
            save(copy(delivery, "delivered", attempts, 0, null));
        } catch (MonitorAlertWebhookClient.UnsafeTargetException
                 | MonitorAlertNotificationSender.DestinationRejectedException e) {
            save(copy(delivery, "failed", attempts, 0, "webhook destination rejected"));
        } catch (RuntimeException ignored) {
            if (attempts >= MAX_ATTEMPTS) {
                save(copy(delivery, "failed", attempts, 0, "webhook delivery failed"));
                return;
            }
            // 暂时性失败会逐步拉长等待时间，并受最大等待时间限制；目标地址校验失败则不再重试。
            long delaySeconds = Math.min(300, 5L << Math.min(attempts - 1, 5));
            long nextAttemptAt = System.currentTimeMillis() + delaySeconds * 1000L;
            save(copy(delivery, "pending", attempts, nextAttemptAt, "webhook delivery failed"));
            redisTemplate.opsForZSet().add(retryKey(), delivery.id(), nextAttemptAt);
            redisTemplate.expire(retryKey(), RETENTION);
        }
    }

    private String destination(MonitorAlertRule rule, MonitorProject project) {
        // 新规则从租户共享的通知地址配置中取 URL；旧规则仍可直接使用自己保存的 webhookUrl。
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
        // 用投递编号找到记录：尚无记录就新增，已有记录就更新状态。
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
        // claim_until 是处理权的截止时间；某个服务实例崩溃后，其他实例可在截止时间过后接手。
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
