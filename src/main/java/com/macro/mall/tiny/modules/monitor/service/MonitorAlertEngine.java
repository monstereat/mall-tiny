package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRecordMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRecord;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MonitorAlertEngine {

    private final MonitorAlertRuleMapper ruleMapper;
    private final MonitorAlertRecordMapper recordMapper;
    private final MonitorProjectMapper projectMapper;
    private final MonitorAlertSilenceService silenceService;
    private final MonitorAlertDeliveryService deliveryService;
    private final StringRedisTemplate redisTemplate;

    public void evaluate(MonitorProject project, MonitorEventEnvelope event, String fingerprint) {
        List<MonitorAlertRule> rules = ruleMapper.selectList(
                Wrappers.<MonitorAlertRule>lambdaQuery()
                        .eq(MonitorAlertRule::getProjectId, project.getId())
                        .eq(MonitorAlertRule::getEnabled, 1)
        );
        for (MonitorAlertRule rule : rules) {
            recordObservation(rule, event);
            evaluateRule(rule, project, fingerprint, System.currentTimeMillis());
        }
    }

    @Scheduled(fixedDelayString = "${monitor.alert.evaluation-interval-ms:1000}")
    public void evaluateActiveRules() {
        List<MonitorAlertRule> rules = ruleMapper.selectList(
                Wrappers.<MonitorAlertRule>lambdaQuery().eq(MonitorAlertRule::getEnabled, 1)
        );
        long now = System.currentTimeMillis();
        for (MonitorAlertRule rule : rules) {
            MonitorProject project = projectMapper.selectById(rule.getProjectId());
            if (project != null && Integer.valueOf(1).equals(project.getStatus())) {
                evaluateRule(rule, project, null, now);
            }
        }
    }

    private void recordObservation(MonitorAlertRule rule, MonitorEventEnvelope event) {
        if ("error_count".equalsIgnoreCase(rule.getMetric())
                && event.getEventType() == MonitorEventType.ERROR) {
            int window = Math.max(60, rule.getWindowSeconds() == null ? 300 : rule.getWindowSeconds());
            long now = System.currentTimeMillis();
            String key = windowKey(rule);
            redisTemplate.opsForZSet().add(key, event.getEventId(), now);
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, now - window * 1000L);
            redisTemplate.expire(key, Duration.ofSeconds(window * 2L));
            return;
        }

        if (event.getEventType() != MonitorEventType.PERFORMANCE || event.getData() == null) {
            return;
        }
        String metric = String.valueOf(event.getData().getOrDefault("metric", ""));
        if (!rule.getMetric().equalsIgnoreCase(metric)) {
            return;
        }
        BigDecimal value = decimal(event.getData().get("value"));
        if (value == null) {
            return;
        }

        String key = metricKey(rule);
        redisTemplate.opsForHash().put(key, "value", value.toPlainString());
        redisTemplate.opsForHash().put(key, "observedAt", Long.toString(System.currentTimeMillis()));
        redisTemplate.expire(key, Duration.ofSeconds(Math.max(60,
                rule.getWindowSeconds() == null ? 300 : rule.getWindowSeconds()) * 2L));
    }

    private void evaluateRule(MonitorAlertRule rule, MonitorProject project, String fingerprint, long now) {
        BigDecimal value = currentValue(rule, now);
        String stateKey = stateKey(rule);
        String sinceValue = (String) redisTemplate.opsForHash().get(stateKey, "since");
        String recordIdValue = (String) redisTemplate.opsForHash().get(stateKey, "recordId");

        if (value == null || !matches(value, rule.getOperator(), rule.getThresholdValue())) {
            if (recordIdValue != null) {
                recover(rule, project, stateKey, recordIdValue, value);
            } else {
                redisTemplate.delete(stateKey);
            }
            return;
        }

        if (sinceValue == null) {
            redisTemplate.opsForHash().put(stateKey, "since", Long.toString(now));
            redisTemplate.opsForHash().put(stateKey, "fingerprint", fingerprint == null ? "" : fingerprint);
            sinceValue = Long.toString(now);
        }
        redisTemplate.opsForHash().put(stateKey, "value", value.toPlainString());

        if (recordIdValue != null) {
            return;
        }
        int durationSeconds = Math.max(0, rule.getDurationSeconds() == null ? 0 : rule.getDurationSeconds());
        if (now - Long.parseLong(sinceValue) < durationSeconds * 1000L) {
            return;
        }

        String activeFingerprint = (String) redisTemplate.opsForHash().get(stateKey, "fingerprint");
        if (silenceService.isSilenced(project.getId(), rule.getId(), activeFingerprint)
                || !acquireFiringLock(rule)) {
            return;
        }
        try {
            if (redisTemplate.opsForHash().hasKey(stateKey, "recordId") || !acquireCooldown(rule)) {
                return;
            }
            MonitorAlertRecord record = createRecord(project, rule, value,
                    StringUtils.hasText(activeFingerprint) ? activeFingerprint : fingerprint);
            try {
                recordMapper.insert(record);
            } catch (RuntimeException e) {
                int cooldown = Math.max(0,
                        rule.getCooldownSeconds() == null ? 900 : rule.getCooldownSeconds());
                if (cooldown > 0) {
                    redisTemplate.delete(cooldownKey(rule));
                }
                throw e;
            }
            redisTemplate.opsForHash().put(stateKey, "recordId", record.getId().toString());
            deliveryService.send(rule, record, project, "firing");
        } finally {
            redisTemplate.delete(firingLockKey(rule));
        }
    }

    private BigDecimal currentValue(MonitorAlertRule rule, long now) {
        if ("error_count".equalsIgnoreCase(rule.getMetric())) {
            int window = Math.max(60, rule.getWindowSeconds() == null ? 300 : rule.getWindowSeconds());
            ZSetOperations<String, String> zset = redisTemplate.opsForZSet();
            String key = windowKey(rule);
            zset.removeRangeByScore(key, 0, now - window * 1000L);
            Long count = zset.zCard(key);
            return BigDecimal.valueOf(count == null ? 0 : count);
        }

        String key = metricKey(rule);
        String observedAt = (String) redisTemplate.opsForHash().get(key, "observedAt");
        String value = (String) redisTemplate.opsForHash().get(key, "value");
        int window = Math.max(60, rule.getWindowSeconds() == null ? 300 : rule.getWindowSeconds());
        if (observedAt == null || value == null || now - Long.parseLong(observedAt) > window * 1000L) {
            return null;
        }
        return decimal(value);
    }

    private boolean matches(BigDecimal value, String operator, BigDecimal threshold) {
        if (threshold == null) {
            return false;
        }
        int compare = value.compareTo(threshold);
        return switch (operator == null ? ">" : operator.trim()) {
            case ">" -> compare > 0;
            case ">=" -> compare >= 0;
            case "<" -> compare < 0;
            case "<=" -> compare <= 0;
            case "==" -> compare == 0;
            default -> false;
        };
    }

    private boolean acquireCooldown(MonitorAlertRule rule) {
        int cooldown = Math.max(0, rule.getCooldownSeconds() == null ? 900 : rule.getCooldownSeconds());
        if (cooldown == 0) {
            return true;
        }
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                cooldownKey(rule), UUID.randomUUID().toString(), Duration.ofSeconds(cooldown));
        return Boolean.TRUE.equals(acquired);
    }

    private boolean acquireFiringLock(MonitorAlertRule rule) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                firingLockKey(rule), UUID.randomUUID().toString(), Duration.ofSeconds(30));
        return Boolean.TRUE.equals(acquired);
    }

    private MonitorAlertRecord createRecord(
            MonitorProject project,
            MonitorAlertRule rule,
            BigDecimal metricValue,
            String fingerprint) {
        MonitorAlertRecord record = new MonitorAlertRecord();
        record.setProjectId(project.getId());
        record.setRuleId(rule.getId());
        record.setMetric(rule.getMetric());
        record.setMetricValue(metricValue);
        record.setThresholdValue(rule.getThresholdValue());
        record.setLevel(rule.getLevel());
        record.setStatus("firing");
        record.setFingerprint(fingerprint);
        record.setTriggeredAt(new Date());
        record.setMessage(rule.getName() + ": " + rule.getMetric() + "=" + metricValue +
                " " + rule.getOperator() + " " + rule.getThresholdValue());
        return record;
    }

    private void recover(MonitorAlertRule rule, MonitorProject project, String stateKey,
                         String recordIdValue, BigDecimal recoveredValue) {
        MonitorAlertRecord record = recordMapper.selectById(Long.parseLong(recordIdValue));
        if (record != null && "firing".equalsIgnoreCase(record.getStatus())) {
            record.setStatus("resolved");
            record.setRecoveredAt(new Date());
            String value = recoveredValue == null ? "unavailable" : recoveredValue.toPlainString();
            record.setMessage(record.getMessage() + "; recovered at value=" + value);
            recordMapper.updateById(record);
            deliveryService.send(rule, record, project, "resolved");
        }
        redisTemplate.delete(stateKey);
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String windowKey(MonitorAlertRule rule) {
        return "monitor:alert:window:" + rule.getProjectId() + ":" + rule.getId();
    }

    private String metricKey(MonitorAlertRule rule) {
        return "monitor:alert:metric:" + rule.getId();
    }

    private String stateKey(MonitorAlertRule rule) {
        return "monitor:alert:state:" + rule.getId();
    }

    private String cooldownKey(MonitorAlertRule rule) {
        return "monitor:alert:cooldown:" + rule.getId();
    }

    private String firingLockKey(MonitorAlertRule rule) {
        return "monitor:alert:firing-lock:" + rule.getId();
    }
}
