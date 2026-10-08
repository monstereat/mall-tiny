package com.macro.mall.tiny.modules.monitor.dto;

import com.macro.mall.tiny.modules.monitor.model.MonitorTenantAuditLog;

import java.util.List;

public record MonitorTenantAuditPage(
        List<MonitorTenantAuditLog> records,
        long total,
        int offset,
        int limit) {
}
