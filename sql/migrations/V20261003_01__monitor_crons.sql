CREATE TABLE IF NOT EXISTS monitor_cron (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  slug VARCHAR(128) NOT NULL,
  name VARCHAR(128) NOT NULL,
  schedule_type VARCHAR(16) NOT NULL,
  schedule VARCHAR(128) NOT NULL,
  timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
  checkin_margin_seconds INT NOT NULL DEFAULT 60,
  max_runtime_seconds INT NOT NULL DEFAULT 1800,
  failure_threshold INT NOT NULL DEFAULT 1,
  recovery_threshold INT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  health_status VARCHAR(16) NOT NULL DEFAULT 'unknown',
  consecutive_failures INT NOT NULL DEFAULT 0,
  consecutive_successes INT NOT NULL DEFAULT 0,
  last_checkin_at DATETIME(3) DEFAULT NULL,
  last_checkin_status VARCHAR(16) DEFAULT NULL,
  next_checkin_at DATETIME(3) NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_cron_project_slug (project_id, slug),
  KEY idx_monitor_cron_due (status, next_checkin_at),
  CONSTRAINT fk_monitor_cron_project FOREIGN KEY (project_id)
    REFERENCES monitor_project (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务监控';

CREATE TABLE IF NOT EXISTS monitor_cron_checkin (
  id BIGINT NOT NULL AUTO_INCREMENT,
  cron_id BIGINT NOT NULL,
  checkin_id VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  environment VARCHAR(64) NOT NULL DEFAULT 'production',
  started_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3) DEFAULT NULL,
  duration_ms BIGINT DEFAULT NULL,
  message VARCHAR(512) DEFAULT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_cron_checkin (cron_id, checkin_id),
  KEY idx_monitor_cron_checkin_history (cron_id, id),
  KEY idx_monitor_cron_checkin_open (cron_id, status, started_at),
  CONSTRAINT fk_monitor_cron_checkin_monitor FOREIGN KEY (cron_id)
    REFERENCES monitor_cron (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务 Check-in 历史';

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_01', 'monitor cron monitors and check-ins');
