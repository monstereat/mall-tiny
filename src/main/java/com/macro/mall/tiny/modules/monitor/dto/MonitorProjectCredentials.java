package com.macro.mall.tiny.modules.monitor.dto;

import com.macro.mall.tiny.modules.monitor.model.MonitorProject;

public record MonitorProjectCredentials(
        MonitorProject project,
        String ingestKey,
        String releaseKey
) {
}
