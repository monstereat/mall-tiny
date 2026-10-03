CREATE TABLE IF NOT EXISTS monitor_replay_storage_usage (
  storage_bucket VARCHAR(255) NOT NULL,
  project_key VARCHAR(64) NOT NULL,
  used_bytes BIGINT NOT NULL DEFAULT 0,
  reconciled_at DATETIME(3) DEFAULT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (storage_bucket, project_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Session Replay per-project object storage usage';

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_20', 'add replay per-project storage usage ledger');
