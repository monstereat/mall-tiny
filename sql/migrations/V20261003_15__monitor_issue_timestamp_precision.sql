-- Preserve millisecond ordering when a new error races with Issue resolution.
ALTER TABLE monitor_issue
  MODIFY COLUMN first_seen DATETIME(3) NOT NULL,
  MODIFY COLUMN last_seen DATETIME(3) NOT NULL,
  MODIFY COLUMN regressed_at DATETIME(3) DEFAULT NULL,
  MODIFY COLUMN resolved_at DATETIME(3) DEFAULT NULL;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_15', 'preserve millisecond precision for Issue event and status timestamps');
