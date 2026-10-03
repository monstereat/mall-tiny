package com.macro.mall.tiny.modules.monitor.dto;

public record MonitorTraceSpan(
        String traceId,
        String spanId,
        String parentSpanId,
        String source,
        String serviceName,
        String kind,
        String op,
        String description,
        long startTime,
        double durationMs,
        String status,
        Long statusCode) {
}
