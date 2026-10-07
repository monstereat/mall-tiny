package com.macro.mall.tiny.modules.monitor.dto;

import java.sql.Timestamp;

public record MonitorTraceOverview(
        String traceId,
        Timestamp firstEventAt,
        Timestamp lastEventAt,
        long eventCount,
        String environment,
        String release,
        String signalTypes) {
}
