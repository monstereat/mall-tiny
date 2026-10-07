-- Read-only inventory of active user-data parts and TTL expiry state.
-- Run with clickhouse-client --multiquery < infra/clickhouse/diagnostics/capacity-report.sql
SELECT
    p.database,
    p.table,
    any(t.engine) AS engine,
    formatReadableSize(sum(p.bytes_on_disk)) AS active_bytes,
    sum(p.rows) AS active_rows,
    count() AS active_parts,
    min(p.min_time) AS oldest_event_time,
    max(p.max_time) AS newest_event_time,
    countIf(p.delete_ttl_info_max > toDateTime(0) AND p.delete_ttl_info_max < now()) AS fully_expired_ttl_parts
FROM system.parts AS p
INNER JOIN system.tables AS t USING (database, table)
WHERE p.active
  AND p.database = 'monitor'
  AND t.engine LIKE '%MergeTree%'
GROUP BY p.database, p.table
ORDER BY sum(p.bytes_on_disk) DESC;

-- Estimate retained table size from the last 30 days of observed rows and
-- each table's current compressed bytes/row. Projections are withheld until
-- the sample spans at least 7 calendar days.
WITH
    retention AS
    (
        SELECT 'error_event' AS table_name, toUInt16(90) AS retention_days
        UNION ALL SELECT 'performance_event', 90
        UNION ALL SELECT 'metric_event', 90
        UNION ALL SELECT 'behavior_event', 30
        UNION ALL SELECT 'profile_event', 30
        UNION ALL SELECT 'span_event', 30
        UNION ALL SELECT 'replay_event', 14
        UNION ALL SELECT 'error_hourly', 365
        UNION ALL SELECT 'error_hourly_correction', 400
    ),
    observed AS
    (
        SELECT table_name, count() AS sample_rows,
            dateDiff('day', min(toDate(event_time)), max(toDate(event_time))) + 1 AS sample_days
        FROM
        (
            SELECT 'error_event' AS table_name, event_time FROM monitor.error_event
            UNION ALL SELECT 'performance_event', event_time FROM monitor.performance_event
            UNION ALL SELECT 'metric_event', event_time FROM monitor.metric_event
            UNION ALL SELECT 'behavior_event', event_time FROM monitor.behavior_event
            UNION ALL SELECT 'profile_event', event_time FROM monitor.profile_event
            UNION ALL SELECT 'span_event', event_time FROM monitor.span_event
            UNION ALL SELECT 'replay_event', event_time FROM monitor.replay_event
            UNION ALL SELECT 'error_hourly', bucket FROM monitor.error_hourly
            UNION ALL SELECT 'error_hourly_correction', bucket FROM monitor.error_hourly_correction
        )
        WHERE event_time >= now() - INTERVAL 30 DAY
        GROUP BY table_name
    ),
    stored AS
    (
        SELECT table AS table_name, sum(rows) AS stored_rows, sum(bytes_on_disk) AS compressed_bytes
        FROM system.parts
        WHERE active AND database = 'monitor'
        GROUP BY table
    )
SELECT
    r.table_name,
    r.retention_days,
    ifNull(o.sample_days, 0) AS sample_days,
    ifNull(o.sample_rows, 0) AS sample_rows,
    ifNull(s.stored_rows, 0) AS active_rows,
    formatReadableSize(ifNull(s.compressed_bytes, 0)) AS active_compressed_bytes,
    if(ifNull(s.stored_rows, 0) = 0, 0,
        round(toFloat64(s.compressed_bytes) / s.stored_rows, 2)) AS compressed_bytes_per_row,
    if(ifNull(o.sample_days, 0) >= 7 AND ifNull(s.stored_rows, 0) > 0,
        round(toFloat64(s.compressed_bytes) / s.stored_rows
            * o.sample_rows / o.sample_days * r.retention_days / 0.70), 0) AS projected_volume_bytes,
    if(ifNull(o.sample_days, 0) >= 7 AND ifNull(s.stored_rows, 0) > 0,
        formatReadableSize(projected_volume_bytes), 'insufficient sample (< 7 days)') AS projection
FROM retention AS r
LEFT JOIN observed AS o USING (table_name)
LEFT JOIN stored AS s USING (table_name)
ORDER BY r.table_name;

SELECT
    d.name AS disk,
    formatReadableSize(d.free_space) AS free_space,
    formatReadableSize(d.total_space) AS total_space,
    round(100 * d.free_space / d.total_space, 2) AS free_percent,
    d.type
FROM system.disks AS d
ORDER BY d.name;

SELECT
    name,
    value
FROM system.merge_tree_settings
WHERE name IN ('storage_policy', 'merge_with_ttl_timeout', 'merge_with_recompression_ttl_timeout', 'ttl_only_drop_parts')
ORDER BY name;
