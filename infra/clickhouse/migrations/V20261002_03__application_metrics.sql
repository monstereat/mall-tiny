CREATE TABLE IF NOT EXISTS monitor.metric_event AS monitor.error_event
ENGINE = ReplacingMergeTree(received_at)
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, event_id, event_time)
TTL event_time + INTERVAL 90 DAY DELETE;
