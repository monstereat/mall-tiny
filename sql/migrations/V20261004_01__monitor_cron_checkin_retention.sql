SET @monitor_cron_completed_retention_ddl = IF(
  EXISTS (
    SELECT 1
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'monitor_cron_checkin'
      AND index_name = 'idx_monitor_cron_checkin_completed_retention'
  ),
  'SELECT 1',
  'ALTER TABLE monitor_cron_checkin ADD KEY idx_monitor_cron_checkin_completed_retention (completed_at, id)'
);
PREPARE monitor_cron_completed_retention_statement FROM @monitor_cron_completed_retention_ddl;
EXECUTE monitor_cron_completed_retention_statement;
DEALLOCATE PREPARE monitor_cron_completed_retention_statement;

SET @monitor_cron_in_progress_retention_ddl = IF(
  EXISTS (
    SELECT 1
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'monitor_cron_checkin'
      AND index_name = 'idx_monitor_cron_checkin_in_progress_retention'
  ),
  'SELECT 1',
  'ALTER TABLE monitor_cron_checkin ADD KEY idx_monitor_cron_checkin_in_progress_retention (status, started_at, id)'
);
PREPARE monitor_cron_in_progress_retention_statement FROM @monitor_cron_in_progress_retention_ddl;
EXECUTE monitor_cron_in_progress_retention_statement;
DEALLOCATE PREPARE monitor_cron_in_progress_retention_statement;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261004_01', 'monitor cron check-in history retention index');
