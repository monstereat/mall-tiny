package com.macro.mall.tiny.modules.monitor.model;

public record MonitorAlertDelivery(
        String id,
        Long projectId,
        Long ruleId,
        Long alertRecordId,
        String alertStatus,
        String status,
        int attempts,
        long nextAttemptAt,
        long createdAt,
        long updatedAt,
        String lastError
) {
}
