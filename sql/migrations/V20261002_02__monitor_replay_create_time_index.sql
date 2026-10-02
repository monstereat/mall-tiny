-- Index Replay retention scans by their storage-aligned creation time.
-- Apply after sql/monitor.sql; safe to re-run.

SET @monitor_replay_index_ddl = IF(
  EXISTS (
    SELECT 1
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'monitor_replay'
      AND index_name = 'idx_replay_create_time'
  ),
  'SELECT 1',
  'ALTER TABLE monitor_replay ADD INDEX idx_replay_create_time (create_time)'
);

PREPARE monitor_replay_index_statement FROM @monitor_replay_index_ddl;
EXECUTE monitor_replay_index_statement;
DEALLOCATE PREPARE monitor_replay_index_statement;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261002_02', 'monitor replay index retention');
