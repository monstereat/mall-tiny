package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRecordMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRecord;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MonitorAlertEngine {

    private final MonitorAlertRuleMapper ruleMapper;
    private final MonitorAlertRecordMapper recordMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void evaluate(MonitorProject project, MonitorEventEnvelope event, String fingerprint) {
        List<MonitorAlertRule> rules = ruleMapper.selectList(
                Wrappers.<MonitorAlertRule>lambdaQuery()
                        .eq(MonitorAlertRule::getProjectId, project.getId())
                        .eq(MonitorAlertRule::getEnabled, 1)
        );
        for (MonitorAlertRule rule : rules) {
            BigDecimal metricValue = metricValue(project, rule, event);
            if (metricValue == null || !matches(metricValue, rule.getOperator(), rule.getThresholdValue())) {
                continue;
            }
            if (!acquireCooldown(rule)) {
                continue;
            }
            MonitorAlertRecord record = createRecord(project, rule, metricValue, fingerprint);
            recordMapper.insert(record);
            notifyWebhook(rule, record, project);
        }
    }

    private BigDecimal metricValue(MonitorProject project, MonitorAlertRule rule, MonitorEventEnvelope event) {
        if ("error_count".equalsIgnoreCase(rule.getMetric()) && event.getEventType() == MonitorEventType.ERROR) {
            int window = Math.max(60, rule.getWindowSeconds() == null ? 300 : rule.getWindowSeconds());
            long now = event.getTimestamp();
            String key = "monitor:alert:window:" + project.getId() + ":" + rule.getId();
            redisTemplate.opsForZSet().add(key, event.getEventId(), now);
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, now - window * 1000L);
            redisTemplate.expire(key, Duration.ofSeconds(window * 2L));
            Long count = redisTemplate.opsForZSet().zCard(key);
            return BigDecimal.valueOf(count == null ? 0 : count);
        }

        if (event.getEventType() == MonitorEventType.PERFORMANCE && event.getData() != null) {
            String metric = String.valueOf(event.getData().getOrDefault("metric", ""));
            if (rule.getMetric().equalsIgnoreCase(metric)) {
                Object value = event.getData().get("value");
                if (value instanceof Number number) {
                    return BigDecimal.valueOf(number.doubleValue());
                }
                if (value != null) {
                    try {
                        return new BigDecimal(String.valueOf(value));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private boolean matches(BigDecimal value, String operator, BigDecimal threshold) {
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
        int cooldown = Math.max(60, rule.getCooldownSeconds() == null ? 900 : rule.getCooldownSeconds());
        String key = "monitor:alert:cooldown:" + rule.getId();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, UUID.randomUUID().toString(), Duration.ofSeconds(cooldown));
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

    private void notifyWebhook(MonitorAlertRule rule, MonitorAlertRecord record, MonitorProject project) {
        if (!StringUtils.hasText(rule.getWebhookUrl())) {
            return;
        }
        try {
            RestClient.create().post()
                    .uri(rule.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "project", project.getProjectKey(),
                            "rule", rule.getName(),
                            "level", rule.getLevel(),
                            "metric", rule.getMetric(),
                            "value", record.getMetricValue(),
                            "threshold", record.getThresholdValue(),
                            "message", record.getMessage()
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ignored) {
            // 告警入库优先，Webhook 失败不回滚消费链路。
        }
    }
}
