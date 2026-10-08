package com.macro.mall.tiny.modules.monitor.dto;

import java.util.Date;
import java.util.List;

public record MonitorTenantRoleView(
        Long id,
        Long tenantId,
        String roleKey,
        String name,
        List<String> permissions,
        Date createTime,
        Date updateTime
) {
}
