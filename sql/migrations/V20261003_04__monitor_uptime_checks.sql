CREATE TABLE IF NOT EXISTS monitor_uptime_check (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  slug VARCHAR(128) NOT NULL,
  name VARCHAR(128) NOT NULL,
  url VARCHAR(2048) NOT NULL,
  method VARCHAR(8) NOT NULL DEFAULT 'GET',
  interval_seconds INT NOT NULL DEFAULT 60,
  timeout_ms INT NOT NULL DEFAULT 5000,
  expected_status_code SMALLINT NOT NULL DEFAULT 200,
  failure_threshold INT NOT NULL DEFAULT 1,
  recovery_threshold INT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  current_status VARCHAR(16) NOT NULL DEFAULT 'unknown',
  consecutive_failures INT NOT NULL DEFAULT 0,
  consecutive_successes INT NOT NULL DEFAULT 0,
  checked_at DATETIME(3) DEFAULT NULL,
  last_status_code SMALLINT DEFAULT NULL,
  last_duration_ms BIGINT DEFAULT NULL,
  last_error VARCHAR(512) DEFAULT NULL,
  next_check_at DATETIME(3) NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_uptime_project_slug (project_id, slug),
  KEY idx_monitor_uptime_due (status, next_check_at),
  KEY idx_monitor_uptime_project (project_id, id),
  CONSTRAINT fk_monitor_uptime_project FOREIGN KEY (project_id)
    REFERENCES monitor_project (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HTTP 可用性监控';

CREATE TABLE IF NOT EXISTS monitor_uptime_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  uptime_check_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  checked_at DATETIME(3) NOT NULL,
  response_status SMALLINT DEFAULT NULL,
  duration_ms BIGINT NOT NULL,
  message VARCHAR(512) DEFAULT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_monitor_uptime_history_check (uptime_check_id, id),
  KEY idx_monitor_uptime_history_checked_at (checked_at),
  CONSTRAINT fk_monitor_uptime_history_check FOREIGN KEY (uptime_check_id)
    REFERENCES monitor_uptime_check (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HTTP 可用性检查历史';

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_04', 'monitor uptime HTTP checks and history');
