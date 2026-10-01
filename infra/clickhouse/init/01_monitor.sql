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
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, toDate(event_time), fingerprint, event_time)
TTL event_time + INTERVAL 90 DAY DELETE;

CREATE TABLE IF NOT EXISTS monitor.performance_event AS monitor.error_event
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, toDate(event_time), event_time)
TTL event_time + INTERVAL 90 DAY DELETE;

CREATE TABLE IF NOT EXISTS monitor.behavior_event AS monitor.error_event
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, toDate(event_time), event_time)
TTL event_time + INTERVAL 30 DAY DELETE;

CREATE TABLE IF NOT EXISTS monitor.replay_event AS monitor.error_event
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, toDate(event_time), session_id, event_time)
TTL event_time + INTERVAL 14 DAY DELETE;

CREATE TABLE IF NOT EXISTS monitor.error_hourly
(
    bucket DateTime,
    project_id LowCardinality(String),
    fingerprint String,
    event_count UInt64
)
ENGINE = SummingMergeTree
PARTITION BY toYYYYMM(bucket)
ORDER BY (project_id, fingerprint, bucket);

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
