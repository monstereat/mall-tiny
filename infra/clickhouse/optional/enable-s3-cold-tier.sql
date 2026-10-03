-- Opt-in migration for deployments that have mounted monitor-s3-cold.xml.
-- Moves data older than 7 days to S3 while preserving each table's existing DELETE TTL.
-- Run only after confirming the cold-tier query SLA and S3 bucket lifecycle/backup policy.
ALTER TABLE monitor.error_event MODIFY SETTING storage_policy = 'monitor_hot_cold';
ALTER TABLE monitor.error_event MODIFY TTL
    event_time + INTERVAL 7 DAY TO VOLUME 'cold',
    event_time + INTERVAL 90 DAY DELETE;

ALTER TABLE monitor.performance_event MODIFY SETTING storage_policy = 'monitor_hot_cold';
ALTER TABLE monitor.performance_event MODIFY TTL
    event_time + INTERVAL 7 DAY TO VOLUME 'cold',
    event_time + INTERVAL 90 DAY DELETE;

ALTER TABLE monitor.metric_event MODIFY SETTING storage_policy = 'monitor_hot_cold';
ALTER TABLE monitor.metric_event MODIFY TTL
    event_time + INTERVAL 7 DAY TO VOLUME 'cold',
    event_time + INTERVAL 90 DAY DELETE;

ALTER TABLE monitor.behavior_event MODIFY SETTING storage_policy = 'monitor_hot_cold';
ALTER TABLE monitor.behavior_event MODIFY TTL
    event_time + INTERVAL 7 DAY TO VOLUME 'cold',
    event_time + INTERVAL 30 DAY DELETE;

ALTER TABLE monitor.profile_event MODIFY SETTING storage_policy = 'monitor_hot_cold';
ALTER TABLE monitor.profile_event MODIFY TTL
    event_time + INTERVAL 7 DAY TO VOLUME 'cold',
    event_time + INTERVAL 30 DAY DELETE;

ALTER TABLE monitor.replay_event MODIFY SETTING storage_policy = 'monitor_hot_cold';
ALTER TABLE monitor.replay_event MODIFY TTL
    event_time + INTERVAL 7 DAY TO VOLUME 'cold',
    event_time + INTERVAL 14 DAY DELETE;

ALTER TABLE monitor.error_hourly MODIFY SETTING storage_policy = 'monitor_hot_cold';
ALTER TABLE monitor.error_hourly MODIFY TTL
    bucket + INTERVAL 30 DAY TO VOLUME 'cold',
    bucket + INTERVAL 365 DAY DELETE;

ALTER TABLE monitor.error_hourly_correction MODIFY SETTING storage_policy = 'monitor_hot_cold';
ALTER TABLE monitor.error_hourly_correction MODIFY TTL
    bucket + INTERVAL 30 DAY TO VOLUME 'cold',
    bucket + INTERVAL 400 DAY DELETE;
