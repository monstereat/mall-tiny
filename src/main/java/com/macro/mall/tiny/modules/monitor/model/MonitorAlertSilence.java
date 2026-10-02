package com.macro.mall.tiny.modules.monitor.model;

public record MonitorAlertSilence(
        String id,
        Long projectId,
        String scope,
        Long ruleId,
        String fingerprint,
        String reason,
        long createdAt,
        long expiresAt
) {
}
