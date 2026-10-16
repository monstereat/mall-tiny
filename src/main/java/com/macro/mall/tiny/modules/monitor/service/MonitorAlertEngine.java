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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * 判断告警规则现在应该处于什么状态：条件满足并持续一段时间后触发，条件恢复后关闭告警。
 * {@code @Scheduled} 表示由 Spring 后台定时调用；新事件到达时，也会由事件消费者立即检查相关规则。
 */
@Service
@RequiredArgsConstructor
public class MonitorAlertEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorAlertEngine.class);

    private final MonitorAlertRuleMapper ruleMapper;
    private final MonitorAlertRecordMapper recordMapper;
    private final MonitorProjectMapper projectMapper;
    private final MonitorAlertSilenceService silenceService;
    private final MonitorAlertDeliveryService deliveryService;
    private final MonitorLogQueryService logQueryService;
    private final StringRedisTemplate redisTemplate;

    public void evaluate(MonitorProject project, MonitorEventEnvelope event, String fingerprint) {
        // 每条事件处理时会调用这里，只检查当前项目启用的规则，不扫描所有项目。
        // 日志类规则使用另一种查询方式，问题新建/复发通知也有独立流程，不在这里重复处理。
        // cd 查询规则
        List<MonitorAlertRule> rules = ruleMapper.selectList(
                Wrappers.<MonitorAlertRule>lambdaQuery()
                        .eq(MonitorAlertRule::getProjectId, project.getId())
                        .eq(MonitorAlertRule::getEnabled, 1)
        );
        for (MonitorAlertRule rule : rules) {
            if (isLogErrorMetric(rule) || isIssueEventMetric(rule)) continue;
            recordObservation(rule, event);
            evaluateRule(rule, project, fingerprint, System.currentTimeMillis());
        }
    }

    @Scheduled(fixedDelayString = "${monitor.alert.evaluation-interval-ms:1000}")
    /** 定时再检查事件类规则；即使之后没有新事件，也能在条件持续时间达到后触发告警。 */
    public void evaluateActiveRules() {
        List<MonitorAlertRule> rules = ruleMapper.selectList(
                Wrappers.<MonitorAlertRule>lambdaQuery().eq(MonitorAlertRule::getEnabled, 1)
        );
        long now = System.currentTimeMillis();
        for (MonitorAlertRule rule : rules) {
            if (isLogErrorMetric(rule) || isIssueEventMetric(rule)) continue;
            MonitorProject project = projectMapper.selectById(rule.getProjectId());
            if (project != null && Integer.valueOf(1).equals(project.getStatus())) {
                evaluateRule(rule, project, null, now);
            }
        }
    }

    @Scheduled(fixedDelayString = "${monitor.alert.logs-evaluation-interval-ms:30000}")
    /** 日志查询比较耗时，所以单独低频运行；Redis 锁保证多个服务实例不会同时查同一条规则。 */
    public void evaluateLogErrorRules() {
        List<MonitorAlertRule> rules = ruleMapper.selectList(
                Wrappers.<MonitorAlertRule>lambdaQuery().eq(MonitorAlertRule::getEnabled, 1)
        );
        for (MonitorAlertRule rule : rules) {
            if (!isLogErrorMetric(rule)) continue;
            MonitorProject project = projectMapper.selectById(rule.getProjectId());
            if (project == null || !Integer.valueOf(1).equals(project.getStatus())) continue;
            String lockKey = logEvaluationLockKey(rule);
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                    lockKey, "1", Duration.ofSeconds(60));
            if (!Boolean.TRUE.equals(locked)) continue;
            try {
                BigDecimal value = logQueryService.countErrors(project,
                        rule.getWindowSeconds() == null ? 300 : rule.getWindowSeconds());
                String key = metricKey(rule);
                redisTemplate.opsForHash().put(key, "value", value.toPlainString());
                redisTemplate.opsForHash().put(key, "observedAt", Long.toString(System.currentTimeMillis()));
                redisTemplate.expire(key, Duration.ofSeconds(Math.max(60,
                        rule.getWindowSeconds() == null ? 300 : rule.getWindowSeconds()) * 2L));
                evaluateRule(rule, project, null, System.currentTimeMillis());
            } catch (RuntimeException e) {
                LOGGER.warn("Log alert evaluation failed for rule id={}", rule.getId(), e);
            } finally {
                redisTemplate.delete(lockKey);
            }
        }
    }

    private void recordObservation(MonitorAlertRule rule, MonitorEventEnvelope event) {
        // 错误数和接口失败数按发生时间存入 Redis，并删除窗口之外的旧记录，只统计最近一段时间。
        if (("error_count".equalsIgnoreCase(rule.getMetric())
                && event.getEventType() == MonitorEventType.ERROR)
                || ("api_failure_count".equalsIgnoreCase(rule.getMetric()) && isApiFailure(event))) {
            int window = Math.max(60, rule.getWindowSeconds() == null ? 300 : rule.getWindowSeconds());
            long now = System.currentTimeMillis();
            String key = windowKey(rule);
            redisTemplate.opsForZSet().add(key, event.getEventId(), now);
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, now - window * 1000L);
            redisTemplate.expire(key, Duration.ofSeconds(window * 2L));
            return;
        }

        if ((event.getEventType() != MonitorEventType.PERFORMANCE
                && event.getEventType() != MonitorEventType.METRIC) || event.getData() == null) {
            return;
        }
        // 性能和自定义指标只保留最近一次有效数值；超过规则的时间范围后就当作没有新数据。
        String metricKey = event.getEventType() == MonitorEventType.METRIC ? "name" : "metric";
        String metric = String.valueOf(event.getData().getOrDefault(metricKey, ""));
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

    static boolean isApiFailure(MonitorEventEnvelope event) {
        // 前端把接口请求结果当作行为事件上报：0 代表网络请求失败，HTTP 4xx/5xx 代表服务返回错误。
        if (event == null || event.getEventType() != MonitorEventType.BEHAVIOR || event.getData() == null
                || !"api".equalsIgnoreCase(String.valueOf(event.getData().get("category")))) {
            return false;
        }
        BigDecimal status = decimalValue(event.getData().get("status"));
        return status != null && (status.compareTo(BigDecimal.ZERO) == 0
                || status.compareTo(BigDecimal.valueOf(400)) >= 0);
    }

    private void evaluateRule(MonitorAlertRule rule, MonitorProject project, String fingerprint, long now) {
        // Redis 保存规则当前状态：since 记下本轮超标从何时开始，recordId 指向已经触发、等待恢复的告警。
        BigDecimal value = currentValue(rule, now);
        String stateKey = stateKey(rule);
        String sinceValue = (String) redisTemplate.opsForHash().get(stateKey, "since");
        String recordIdValue = (String) redisTemplate.opsForHash().get(stateKey, "recordId");

        if (value == null || !matches(value, rule.getOperator(), rule.getThresholdValue())) {
            // 数值回到正常范围就关闭已触发的告警；还没触发的临时计时也一并清掉。
            if (recordIdValue != null) {
                recover(rule, project, stateKey, recordIdValue, value);
            } else {
                redisTemplate.delete(stateKey);
            }
            return;
        }

        if (sinceValue == null) {
            // 如果规则要求持续一段时间，第一次超标时只记开始时间，等时间真的够了再触发。
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
            // 短锁和冷却时间避免多个线程重复建告警；成功后保存告警编号，等条件恢复时更新这条记录。
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
        // 数量规则统计最近时间范围内的事件；数值规则读取最近一次观测，过期数据不参与判断。
        if ("error_count".equalsIgnoreCase(rule.getMetric())
                || "api_failure_count".equalsIgnoreCase(rule.getMetric())) {
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

    private boolean isLogErrorMetric(MonitorAlertRule rule) {
        return "log_error_count".equalsIgnoreCase(rule.getMetric());
    }

    private boolean isIssueEventMetric(MonitorAlertRule rule) {
        return "new_issue".equalsIgnoreCase(rule.getMetric())
                || "issue_regression".equalsIgnoreCase(rule.getMetric());
    }

    private boolean matches(BigDecimal value, String operator, BigDecimal threshold) {
        // 用十进制数比较当前值和阈值，避免小数转成 double 后出现细微误差。
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
        // 给处理权设置短暂截止时间，避免多个线程或服务实例同时为同一规则新建告警。
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
        // 指标恢复正常后更新原告警记录、发送恢复通知，并清除 Redis 里的活动状态。
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
        return decimalValue(value);
    }

    private static BigDecimal decimalValue(Object value) {
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

    private String logEvaluationLockKey(MonitorAlertRule rule) {
        return "monitor:alert:logs-poll:" + rule.getId();
    }
}
