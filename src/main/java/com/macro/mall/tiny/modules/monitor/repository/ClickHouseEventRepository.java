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

/**
 * 原始事件写入层：这里把后台处理完的事件批量存入 ClickHouse（分析查询库）。
 * 普通业务表通过 MyBatis Mapper 访问；ClickHouse 这边使用专属 JdbcTemplate 执行 SQL。
 */
@Repository
public class ClickHouseEventRepository {

    /** 一行待写数据：event 保存事件全部内容，fingerprint（错误分组编号）只在错误事件时有值。 */
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

        // 不同类型写入不同表；先按类型分组，再一次批量写一组，避免每条事件单独请求数据库。
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
        // SQL 中的问号由数据库驱动安全填入字段值；表名不能用问号代替，所以必须由下面的固定分支选择。
        MonitorEventEnvelope event = row.event();
        int index = 1;
        ps.setString(index++, event.getEventId());
        ps.setString(index++, event.getProjectId());
        // SDK 给的是毫秒数字；先转成 Java 时间对象，再写入 ClickHouse 的时间列。
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
        // 数据库参数只能填字段值、不能填表名；因此表名必须由固定分支决定，不能拼接用户传来的文本。
        return switch (type) {
            case ERROR -> "error_event";
            case PERFORMANCE -> "performance_event";
            case BEHAVIOR -> "behavior_event";
            case REPLAY -> "replay_event";
            case METRIC -> "metric_event";
            case PROFILE -> "profile_event";
            case SPAN -> "span_event";
        };
    }

    private String toJson(MonitorEventEnvelope event) {
        try {
            // 同时保存完整事件 JSON，查询时可按 JSON 字段路径读取错误、性能等类型自己的内容。
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("monitor event payload serialization failed", e);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
