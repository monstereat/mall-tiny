CREATE DATABASE IF NOT EXISTS monitor;

CREATE TABLE IF NOT EXISTS monitor.error_event
(
    event_id String,
    project_id LowCardinality(String),
    event_time DateTime64(3),
    received_at DateTime64(3) DEFAULT now64(3),
    session_id String,
    user_id String,
    release String,
    environment LowCardinality(String),
    page_url String,
    sdk_version String,
    trace_id String,
    fingerprint String,
    payload String
)
ENGINE = ReplacingMergeTree(received_at)
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, event_id, event_time)
TTL event_time + INTERVAL 90 DAY DELETE;

CREATE TABLE IF NOT EXISTS monitor.performance_event AS monitor.error_event
ENGINE = ReplacingMergeTree(received_at)
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, event_id, event_time)
TTL event_time + INTERVAL 90 DAY DELETE;

CREATE TABLE IF NOT EXISTS monitor.behavior_event AS monitor.error_event
ENGINE = ReplacingMergeTree(received_at)
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, event_id, event_time)
TTL event_time + INTERVAL 30 DAY DELETE;

CREATE TABLE IF NOT EXISTS monitor.replay_event AS monitor.error_event
ENGINE = ReplacingMergeTree(received_at)
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, event_id, event_time)
TTL event_time + INTERVAL 14 DAY DELETE;

CREATE TABLE IF NOT EXISTS monitor.metric_event AS monitor.error_event
ENGINE = ReplacingMergeTree(received_at)
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, event_id, event_time)
TTL event_time + INTERVAL 90 DAY DELETE;

CREATE TABLE IF NOT EXISTS monitor.profile_event AS monitor.error_event
ENGINE = ReplacingMergeTree(received_at)
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, event_id, event_time)
TTL event_time + INTERVAL 30 DAY DELETE;

CREATE TABLE IF NOT EXISTS monitor.span_event AS monitor.error_event
ENGINE = ReplacingMergeTree(received_at)
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, event_id, event_time)
TTL event_time + INTERVAL 30 DAY DELETE;

CREATE TABLE IF NOT EXISTS monitor.error_hourly
(
    bucket DateTime,
    project_id LowCardinality(String),
    fingerprint String,
    event_count UInt64
)
ENGINE = SummingMergeTree
PARTITION BY toYYYYMM(bucket)
ORDER BY (project_id, fingerprint, bucket)
TTL bucket + INTERVAL 365 DAY DELETE;

CREATE MATERIALIZED VIEW IF NOT EXISTS monitor.error_hourly_mv
TO monitor.error_hourly
AS
SELECT
    toStartOfHour(event_time) AS bucket,
    project_id,
    fingerprint,
    count() AS event_count
FROM monitor.error_event
GROUP BY bucket, project_id, fingerprint;

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
ORDER BY (deletion_job_id, correction_id)
TTL bucket + INTERVAL 400 DAY DELETE;

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
    WHERE bucket >= now() - INTERVAL 365 DAY
    UNION ALL
    SELECT bucket, project_id, fingerprint, event_count
    FROM monitor.error_hourly_correction FINAL
    WHERE bucket >= now() - INTERVAL 365 DAY
)
GROUP BY bucket, project_id, fingerprint;
