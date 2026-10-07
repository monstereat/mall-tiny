SET @monitor_issue_has_regressed_at = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'monitor_issue'
    AND column_name = 'regressed_at'
);
SET @monitor_issue_regression_ddl = IF(
  @monitor_issue_has_regressed_at = 0,
  'ALTER TABLE monitor_issue ADD COLUMN regressed_at DATETIME(3) DEFAULT NULL AFTER latest_release',
  'SELECT 1'
);
PREPARE monitor_issue_regression_column_stmt FROM @monitor_issue_regression_ddl;
EXECUTE monitor_issue_regression_column_stmt;
DEALLOCATE PREPARE monitor_issue_regression_column_stmt;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_13', 'track Issue regressions after resolution');
