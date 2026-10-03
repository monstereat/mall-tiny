package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionItem;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionJob;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Writes stable correction rows; ReplacingMergeTree FINAL makes retries idempotent across MySQL/ClickHouse failures. */
@Service
@RequiredArgsConstructor
public class MonitorErrorHourlyDeletionReconciler {
    private static final String INSERT_SQL = "INSERT INTO monitor.error_hourly_correction " +
            "(deletion_job_id, correction_id, bucket, project_id, fingerprint, event_count) VALUES (?, ?, toDateTime(?), ?, ?, ?)";

    @Qualifier("clickHouseJdbcTemplate")
    private final JdbcTemplate clickHouse;
    private final ObjectMapper objectMapper;

    public void reconcile(MonitorDataDeletionJob job, List<MonitorDataDeletionItem> items) {
        if (items == null || items.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>(items.size());
        for (MonitorDataDeletionItem item : items) {
            if (item.getId() == null) throw new IllegalArgumentException("error_hourly snapshot item must have an id");
            JsonNode snapshot = parse(item.getItemValue());
            long bucketEpoch = snapshot.path("bucketEpoch").asLong(Long.MIN_VALUE);
            long eventCount = snapshot.path("eventCount").asLong(Long.MIN_VALUE);
            String fingerprint = snapshot.path("fingerprint").asText();
            if (bucketEpoch < 0 || eventCount < 1 || fingerprint.isBlank()) {
                throw new IllegalArgumentException("invalid error_hourly correction snapshot item " + item.getId());
            }
            rows.add(new Object[]{job.getId(), item.getId(), bucketEpoch,
                    job.getProjectKey(), fingerprint, -eventCount});
        }
        clickHouse.batchUpdate(INSERT_SQL, rows);
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid error_hourly correction snapshot JSON", e);
        }
    }
}
