package com.macro.mall.tiny.modules.monitor.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class ClickHouseEventRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ClickHouseEventRepository(
            @Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(MonitorEventEnvelope event, String fingerprint) {
        String table = resolveTable(event.getEventType());
        String sql = "INSERT INTO monitor." + table + " " +
                "(event_id, project_id, event_time, session_id, user_id, release, environment, page_url, sdk_version, trace_id, fingerprint, payload) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(
                sql,
                event.getEventId(),
                event.getProjectId(),
                Timestamp.from(Instant.ofEpochMilli(event.getTimestamp())),
                safe(event.getSessionId()),
                safe(event.getUserId()),
                safe(event.getRelease()),
                safe(event.getEnvironment()),
                safe(event.getPageUrl()),
                safe(event.getSdkVersion()),
                safe(event.getTraceId()),
                safe(fingerprint),
                toJson(event)
        );
    }

    private String resolveTable(MonitorEventType type) {
        return switch (type) {
            case ERROR -> "error_event";
            case PERFORMANCE -> "performance_event";
            case BEHAVIOR -> "behavior_event";
            case REPLAY -> "replay_event";
        };
    }

    private String toJson(MonitorEventEnvelope event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("monitor event payload serialization failed", e);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
