package com.macro.mall.tiny.modules.monitor.dto;

import java.util.List;

public record MonitorDashboardView(
        Long id,
        String name,
        List<Long> queryIds,
        Long createdBy,
        boolean canModify
) {
}
