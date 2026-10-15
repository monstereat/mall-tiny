package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRecordMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRecord;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssue;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * 把单条错误事件合并到 MySQL 的问题汇总（Issue）里。
 * 用“项目编号 + 错误指纹”找到对应问题，更新发生次数、时间和影响用户数；新问题或已解决问题再次出现时触发通知。
 */
@Service
@RequiredArgsConstructor
public class MonitorIssueService {

    private static final Duration USER_CARDINALITY_TTL = Duration.ofDays(90);
    private static final Duration ISSUE_EVENT_DEDUP_TTL = Duration.ofDays(7);
    private static final Duration ISSUE_TRANSITION_LOCK_TTL = Duration.ofSeconds(30);
    private static final String NEW_ISSUE_METRIC = "new_issue";
    private static final String ISSUE_REGRESSION_METRIC = "issue_regression";
    private static final RedisScript<Long> RELEASE_ISSUE_LOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final MonitorIssueMapper issueMapper;
    private final MonitorAlertRuleMapper alertRuleMapper;
    private final MonitorAlertRecordMapper alertRecordMapper;
    private final MonitorAlertDeliveryService alertDeliveryService;
    private final MonitorAlertSilenceService alertSilenceService;
    private final StringRedisTemplate redisTemplate;

    public void aggregate(MonitorProject project, MonitorEventEnvelope event, String fingerprint) {
        // 第一层按事件编号去重，避免 Kafka 重发同一条错误后把次数和用户数再加一遍。
        String processedKey = "monitor:issue:aggregated:" + event.getEventId();
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(processedKey, "1", ISSUE_EVENT_DEDUP_TTL);
        if (!Boolean.TRUE.equals(first)) {
            return;
        }

        // 第二层按问题指纹加锁：不同错误可能同时到达；同一个问题的新建/恢复判断要一个接一个处理。
        String lockKey = "monitor:issue:transition:" + project.getId() + ":" + fingerprint;
        String lockOwner = UUID.randomUUID().toString();
        Boolean locked;
        try {
            locked = redisTemplate.opsForValue().setIfAbsent(lockKey, lockOwner, ISSUE_TRANSITION_LOCK_TTL);
        } catch (RuntimeException ex) {
            redisTemplate.delete(processedKey);
            throw ex;
        }
        if (!Boolean.TRUE.equals(locked)) {
            redisTemplate.delete(processedKey);
            throw new IllegalStateException("issue transition is being processed; retry event");
        }

        try {
            // 先读取更新前状态，判断这是新问题还是已解决后复发；随后用 upsert 一次完成新增或更新汇总行。
            MonitorIssue existing = issueMapper.selectOne(Wrappers.<MonitorIssue>lambdaQuery()
                    .eq(MonitorIssue::getProjectId, project.getId())
                    .eq(MonitorIssue::getFingerprint, fingerprint)
                    .last("LIMIT 1"));
            boolean isNew = existing == null;
            boolean isRegression = existing != null && "resolved".equals(existing.getStatus())
                    && (existing.getResolvedAt() == null
                    || event.getTimestamp() > existing.getResolvedAt().getTime());
            boolean retryingFirstNotification = existing != null
                    && sameEventTime(existing.getFirstSeen(), event.getTimestamp());
            boolean retryingRegressionNotification = existing != null
                    && sameEventTime(existing.getRegressedAt(), event.getTimestamp());
            long affectedUsers = updateAffectedUsers(project.getId(), fingerprint, event.getUserId());
            // upsert 的意思是“没有就新增，有就更新”；它会更新首次/最近发生时间等汇总信息。
            issueMapper.upsert(
                    project.getId(),
                    fingerprint,
                    resolveTitle(event.getData()),
                    affectedUsers,
                    new Date(event.getTimestamp()),
                    event.getRelease()
            );
            if (isNew || retryingFirstNotification) {
                notifyIssueRules(project, event, fingerprint, NEW_ISSUE_METRIC, "New issue");
            } else if (isRegression || retryingRegressionNotification) {
                notifyIssueRules(project, event, fingerprint, ISSUE_REGRESSION_METRIC, "Issue regressed");
            }
        } catch (RuntimeException ex) {
            // 出错时删除事件编号的去重记录，再把异常交还 Kafka；这样重试时不会被当成已处理。
            redisTemplate.delete(processedKey);
            throw ex;
        } finally {
            releaseTransitionLock(lockKey, lockOwner);
        }
    }

    void releaseTransitionLock(String lockKey, String lockOwner) {
        // Lua 脚本一次完成“确认锁还是自己持有，再删除”；避免删掉其他线程后来拿到的新锁。
        redisTemplate.execute(RELEASE_ISSUE_LOCK, List.of(lockKey), lockOwner);
    }

    private void notifyIssueRules(MonitorProject project, MonitorEventEnvelope event, String fingerprint,
                                  String metric, String eventLabel) {
        // 按规则逐条检查通知；在静默期内就跳过，已有记录但消息没发出去时只补发那条消息。
        for (MonitorAlertRule rule : alertRuleMapper.selectList(Wrappers.<MonitorAlertRule>lambdaQuery()
                .eq(MonitorAlertRule::getProjectId, project.getId())
                .eq(MonitorAlertRule::getEnabled, 1)
                .eq(MonitorAlertRule::getMetric, metric))) {
            if (alertSilenceService.isSilenced(project.getId(), rule.getId(), fingerprint)) continue;
            MonitorAlertRecord existing = existingNotificationRecord(project.getId(), rule.getId(), metric,
                    fingerprint, event.getTimestamp());
            if (existing != null) {
                if (!alertDeliveryService.hasDelivery(existing.getId())) {
                    alertDeliveryService.send(rule, existing, project, "firing");
                }
                continue;
            }
            MonitorAlertRecord record = new MonitorAlertRecord();
            record.setProjectId(project.getId());
            record.setRuleId(rule.getId());
            record.setMetric(metric);
            record.setMetricValue(java.math.BigDecimal.ONE);
            record.setThresholdValue(java.math.BigDecimal.ONE);
            record.setLevel(rule.getLevel());
            record.setStatus("resolved");
            record.setFingerprint(fingerprint);
            record.setTriggeredAt(new Date(event.getTimestamp()));
            record.setRecoveredAt(new Date(event.getTimestamp()));
            record.setMessage(truncate(eventLabel + ": " + resolveTitle(event.getData()), 512));
            alertRecordMapper.insert(record);
            alertDeliveryService.send(rule, record, project, "firing");
        }
    }

    private MonitorAlertRecord existingNotificationRecord(Long projectId, Long ruleId, String metric,
                                                          String fingerprint, long eventTimestamp) {
        long secondStart = eventTimestamp - Math.floorMod(eventTimestamp, 1_000L);
        return alertRecordMapper.selectOne(Wrappers.<MonitorAlertRecord>lambdaQuery()
                .eq(MonitorAlertRecord::getProjectId, projectId)
                .eq(MonitorAlertRecord::getRuleId, ruleId)
                .eq(MonitorAlertRecord::getMetric, metric)
                .eq(MonitorAlertRecord::getFingerprint, fingerprint)
                .ge(MonitorAlertRecord::getTriggeredAt, new Date(secondStart))
                .lt(MonitorAlertRecord::getTriggeredAt, new Date(secondStart + 1_000L))
                .orderByAsc(MonitorAlertRecord::getId)
                .last("LIMIT 1"));
    }

    private boolean sameEventTime(Date storedTime, long eventTimestamp) {
        return storedTime != null && storedTime.getTime() == eventTimestamp;
    }

    private long updateAffectedUsers(Long projectId, String fingerprint, String userId) {
        String key = "monitor:issue:users:" + projectId + ":" + fingerprint;
        if (StringUtils.hasText(userId)) {
            // HyperLogLog 是 Redis 的省空间计数器：可以估算不同 userId 的数量，但不能列出用户，也不是精确计数。
            redisTemplate.opsForHyperLogLog().add(key, userId);
            redisTemplate.expire(key, USER_CARDINALITY_TTL);
        }
        Long size = redisTemplate.opsForHyperLogLog().size(key);
        return size == null ? 0L : size;
    }

    private String resolveTitle(Map<String, Object> data) {
        if (data == null) {
            return "Unknown frontend error";
        }
        Object message = data.get("message");
        if (message != null && StringUtils.hasText(String.valueOf(message))) {
            return truncate(String.valueOf(message), 512);
        }
        Object name = data.get("name");
        return name == null ? "Unknown frontend error" : truncate(String.valueOf(name), 512);
    }

    private String truncate(String source, int maxLength) {
        return source.length() <= maxLength ? source : source.substring(0, maxLength);
    }
}
