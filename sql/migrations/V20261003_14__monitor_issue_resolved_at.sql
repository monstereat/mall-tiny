SET @monitor_issue_has_resolved_at = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'monitor_issue'
    AND column_name = 'resolved_at'
);
SET @monitor_issue_resolved_at_ddl = IF(
  @monitor_issue_has_resolved_at = 0,
  'ALTER TABLE monitor_issue ADD COLUMN resolved_at DATETIME(3) DEFAULT NULL AFTER regressed_at',
  'SELECT 1'
);
PREPARE monitor_issue_resolved_at_stmt FROM @monitor_issue_resolved_at_ddl;
EXECUTE monitor_issue_resolved_at_stmt;
DEALLOCATE PREPARE monitor_issue_resolved_at_stmt;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_14', 'track Issue resolution time to reject late regression events');
