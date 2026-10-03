SET @monitor_metric_last_seen_has_column = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'monitor_metric_cardinality_metric'
    AND column_name = 'last_seen'
);
SET @monitor_metric_last_seen_ddl = IF(
  @monitor_metric_last_seen_has_column = 0,
  'ALTER TABLE monitor_metric_cardinality_metric ADD COLUMN last_seen DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)',
  'SELECT 1'
);
PREPARE monitor_metric_last_seen_column_stmt FROM @monitor_metric_last_seen_ddl;
EXECUTE monitor_metric_last_seen_column_stmt;
DEALLOCATE PREPARE monitor_metric_last_seen_column_stmt;

SET @monitor_metric_last_seen_has_index = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'monitor_metric_cardinality_metric'
    AND index_name = 'idx_monitor_metric_cardinality_metric_expiry'
);
SET @monitor_metric_last_seen_ddl = IF(
  @monitor_metric_last_seen_has_index = 0,
  'ALTER TABLE monitor_metric_cardinality_metric ADD KEY idx_monitor_metric_cardinality_metric_expiry (last_seen)',
  'SELECT 1'
);
PREPARE monitor_metric_last_seen_index_stmt FROM @monitor_metric_last_seen_ddl;
EXECUTE monitor_metric_last_seen_index_stmt;
DEALLOCATE PREPARE monitor_metric_last_seen_index_stmt;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_05', 'monitor metric cardinality metric activity timestamp');
