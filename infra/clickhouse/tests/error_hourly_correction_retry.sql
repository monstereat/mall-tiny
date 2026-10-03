-- Isolated synthetic fixture: repeated correction writes must count only once in the effective view.
DROP DATABASE IF EXISTS monitor_error_hourly_correction_test;
CREATE DATABASE monitor_error_hourly_correction_test;

CREATE TABLE monitor_error_hourly_correction_test.error_hourly
(
    bucket DateTime,
    project_id LowCardinality(String),
    fingerprint String,
    event_count UInt64
)
ENGINE = SummingMergeTree
ORDER BY (project_id, fingerprint, bucket);

CREATE TABLE monitor_error_hourly_correction_test.error_hourly_correction
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

CREATE VIEW monitor_error_hourly_correction_test.error_hourly_effective AS
SELECT bucket, project_id, fingerprint,
       toUInt64(greatest(toInt64(0), sum(event_count))) AS event_count
FROM
(
    SELECT bucket, project_id, fingerprint, toInt64(event_count) AS event_count
    FROM monitor_error_hourly_correction_test.error_hourly
    UNION ALL
    SELECT bucket, project_id, fingerprint, event_count
    FROM monitor_error_hourly_correction_test.error_hourly_correction FINAL
)
GROUP BY bucket, project_id, fingerprint;

INSERT INTO monitor_error_hourly_correction_test.error_hourly VALUES
    ('2026-10-02 12:00:00', 'synthetic-project', 'synthetic-fingerprint', 5);

-- Simulates a server-side accepted insert followed by a lost acknowledgement and retry.
INSERT INTO monitor_error_hourly_correction_test.error_hourly_correction VALUES
    (51, 9007, '2026-10-02 12:00:00', 'synthetic-project', 'synthetic-fingerprint', -3);
INSERT INTO monitor_error_hourly_correction_test.error_hourly_correction VALUES
    (51, 9007, '2026-10-02 12:00:00', 'synthetic-project', 'synthetic-fingerprint', -3);

SELECT throwIf(
    (SELECT sum(event_count) FROM monitor_error_hourly_correction_test.error_hourly_effective
      WHERE project_id = 'synthetic-project' AND fingerprint = 'synthetic-fingerprint') != 2,
    'duplicate retry changed effective error_hourly more than once'
);
DROP DATABASE monitor_error_hourly_correction_test;
