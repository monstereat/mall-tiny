-- Keep error aggregates for one year, longer than the 90-day raw error TTL.
-- Keep corrections for an additional 35-day TTL merge grace period. The view
-- applies the 365-day logical horizon to both sources, so old aggregate rows
-- cannot reappear if ClickHouse expires the correction parts first.
ALTER TABLE monitor.error_hourly
    MODIFY TTL bucket + INTERVAL 365 DAY DELETE;

ALTER TABLE monitor.error_hourly_correction
    MODIFY TTL bucket + INTERVAL 400 DAY DELETE;

CREATE OR REPLACE VIEW monitor.error_hourly_effective AS
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
