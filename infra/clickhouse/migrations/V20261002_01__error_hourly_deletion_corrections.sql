-- Idempotent corrections for error_hourly after conditional event deletion.
-- Query error_hourly_effective for counts that include these corrections.
CREATE TABLE IF NOT EXISTS monitor.error_hourly_correction
(
    deletion_job_id UInt64,
    correction_id UInt64,
    bucket DateTime,
    project_id LowCardinality(String),
    fingerprint String,
    event_count Int64
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(bucket)
ORDER BY (deletion_job_id, correction_id);

CREATE VIEW IF NOT EXISTS monitor.error_hourly_effective AS
SELECT
    bucket,
    project_id,
    fingerprint,
    toUInt64(greatest(toInt64(0), sum(event_count))) AS event_count
FROM
(
    SELECT bucket, project_id, fingerprint, toInt64(event_count) AS event_count
    FROM monitor.error_hourly
    UNION ALL
    SELECT bucket, project_id, fingerprint, event_count
    FROM monitor.error_hourly_correction FINAL
)
GROUP BY bucket, project_id, fingerprint;
