package com.macro.mall.tiny.modules.monitor.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class ClickHouseEventRepository {

    public record StoredEvent(MonitorEventEnvelope event, String fingerprint) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ClickHouseEventRepository(
            @Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(MonitorEventEnvelope event, String fingerprint) {
        saveBatch(List.of(new StoredEvent(event, fingerprint)));
    }

    public void saveBatch(List<StoredEvent> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        Map<MonitorEventType, List<StoredEvent>> grouped = rows.stream()
                .collect(Collectors.groupingBy(row -> row.event().getEventType()));

        grouped.forEach((type, group) -> {
            String table = resolveTable(type);
            String sql = "INSERT INTO monitor." + table + " " +
                    "(event_id, project_id, event_time, session_id, user_id, release, environment, page_url, sdk_version, trace_id, fingerprint, payload) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.batchUpdate(
                    sql,
                    group,
                    Math.min(500, group.size()),
                    (ps, row) -> bind(ps, row)
            );
        });
    }

    private void bind(PreparedStatement ps, StoredEvent row) throws SQLException {
        MonitorEventEnvelope event = row.event();
        int index = 1;
        ps.setString(index++, event.getEventId());
        ps.setString(index++, event.getProjectId());
        ps.setTimestamp(index++, Timestamp.from(Instant.ofEpochMilli(event.getTimestamp())));
        ps.setString(index++, safe(event.getSessionId()));
        ps.setString(index++, safe(event.getUserId()));
        ps.setString(index++, safe(event.getRelease()));
        ps.setString(index++, safe(event.getEnvironment()));
        ps.setString(index++, safe(event.getPageUrl()));
        ps.setString(index++, safe(event.getSdkVersion()));
        ps.setString(index++, safe(event.getTraceId()));
        ps.setString(index++, safe(row.fingerprint()));
        ps.setString(index, toJson(event));
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
