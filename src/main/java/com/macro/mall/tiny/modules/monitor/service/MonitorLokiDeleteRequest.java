package com.macro.mall.tiny.modules.monitor.service;

import java.time.Instant;

/** A delete request as returned by Loki's v3.5.x HTTP API. */
public record MonitorLokiDeleteRequest(
        String requestId,
        String query,
        Instant start,
        Instant end,
        Instant createdAt,
        String status,
        Integer progress) {
}
