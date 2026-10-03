-- Isolated synthetic-only check for the aggregate/correction retention contract.
-- It deliberately models an expired correction part with an old positive base row.
DROP DATABASE IF EXISTS monitor_error_hourly_retention_test;
CREATE DATABASE monitor_error_hourly_retention_test;

CREATE TABLE monitor_error_hourly_retention_test.error_hourly
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

CREATE TABLE monitor_error_hourly_retention_test.error_hourly_correction
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

CREATE VIEW monitor_error_hourly_retention_test.error_hourly_effective AS
SELECT
    bucket,
    project_id,
    fingerprint,
    toUInt64(greatest(toInt64(0), sum(event_count))) AS event_count
FROM
(
    SELECT bucket, project_id, fingerprint, toInt64(event_count) AS event_count
    FROM monitor_error_hourly_retention_test.error_hourly
    WHERE bucket >= now() - INTERVAL 365 DAY
    UNION ALL
    SELECT bucket, project_id, fingerprint, event_count
    FROM monitor_error_hourly_retention_test.error_hourly_correction FINAL
    WHERE bucket >= now() - INTERVAL 365 DAY
)
GROUP BY bucket, project_id, fingerprint;

-- Recent data retains only the non-deleted count.
INSERT INTO monitor_error_hourly_retention_test.error_hourly VALUES
    (now() - INTERVAL 1 HOUR, 'synthetic-project', 'recent', 5),
    (now() - INTERVAL 370 DAY, 'synthetic-project', 'stale-uncorrected', 9),
    (now() - INTERVAL 370 DAY, 'synthetic-project', 'stale-corrected', 8);
INSERT INTO monitor_error_hourly_retention_test.error_hourly_correction VALUES
    (1, 1, now() - INTERVAL 1 HOUR, 'synthetic-project', 'recent', -3),
    (1, 2, now() - INTERVAL 370 DAY, 'synthetic-project', 'stale-corrected', -8);

SELECT throwIf(
    (SELECT count() FROM monitor_error_hourly_retention_test.error_hourly_effective
      WHERE project_id = 'synthetic-project' AND fingerprint = 'recent' AND event_count = 2) != 1,
    'recent aggregate did not reflect its deletion correction'
);
SELECT throwIf(
    (SELECT count() FROM monitor_error_hourly_retention_test.error_hourly_effective
      WHERE project_id = 'synthetic-project' AND fingerprint LIKE 'stale-%') != 0,
    'aggregate older than 365 days leaked through effective view'
);
SELECT throwIf(
    (SELECT positionCaseInsensitive(create_table_query, 'toIntervalDay(365)')
       FROM system.tables
       WHERE database = 'monitor_error_hourly_retention_test' AND name = 'error_hourly') = 0,
    'synthetic error_hourly table is missing the 365-day TTL'
);
SELECT throwIf(
    (SELECT positionCaseInsensitive(create_table_query, 'toIntervalDay(400)')
       FROM system.tables
       WHERE database = 'monitor_error_hourly_retention_test' AND name = 'error_hourly_correction') = 0,
    'synthetic correction table is missing the 400-day TTL'
);

DROP DATABASE monitor_error_hourly_retention_test;
