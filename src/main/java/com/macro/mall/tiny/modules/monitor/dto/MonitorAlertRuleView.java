package com.macro.mall.tiny.modules.monitor.dto;

import com.macro.mall.tiny.modules.monitor.model.MonitorAlertRule;

import java.math.BigDecimal;
import java.util.Date;

public record MonitorAlertRuleView(
        Long id,
        Long projectId,
        String name,
        String metric,
        String operator,
        BigDecimal thresholdValue,
        Integer windowSeconds,
        Integer durationSeconds,
        Integer cooldownSeconds,
        String level,
        String webhookUrl,
        Long notificationRouteId,
        Integer enabled,
        Date createTime,
        Date updateTime) {

    public static MonitorAlertRuleView from(MonitorAlertRule rule, boolean includeWebhookUrl) {
        return new MonitorAlertRuleView(rule.getId(), rule.getProjectId(), rule.getName(), rule.getMetric(),
                rule.getOperator(), rule.getThresholdValue(), rule.getWindowSeconds(), rule.getDurationSeconds(),
                rule.getCooldownSeconds(), rule.getLevel(), includeWebhookUrl ? rule.getWebhookUrl() : null,
                rule.getNotificationRouteId(), rule.getEnabled(), rule.getCreateTime(), rule.getUpdateTime());
    }
}
