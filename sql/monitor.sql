CREATE TABLE IF NOT EXISTS monitor_project (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  project_key VARCHAR(64) NOT NULL,
  platform VARCHAR(32) NOT NULL DEFAULT 'web',
  owner_id BIGINT DEFAULT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_key (project_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控项目';

CREATE TABLE IF NOT EXISTS monitor_release (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  version VARCHAR(128) NOT NULL,
  environment VARCHAR(32) NOT NULL DEFAULT 'production',
  git_commit VARCHAR(64) DEFAULT NULL,
  branch_name VARCHAR(128) DEFAULT NULL,
  source_map_status VARCHAR(32) NOT NULL DEFAULT 'pending',
  build_time DATETIME DEFAULT NULL,
  deploy_time DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_release (project_id, version, environment),
  KEY idx_project_deploy_time (project_id, deploy_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布版本';

CREATE TABLE IF NOT EXISTS monitor_issue (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  fingerprint VARCHAR(128) NOT NULL,
  title VARCHAR(512) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'unresolved',
  event_count BIGINT NOT NULL DEFAULT 1,
  affected_users BIGINT NOT NULL DEFAULT 0,
  first_seen DATETIME NOT NULL,
  last_seen DATETIME NOT NULL,
  latest_release VARCHAR(128) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_fingerprint (project_id, fingerprint),
  KEY idx_project_last_seen (project_id, last_seen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常聚合 Issue';

CREATE TABLE IF NOT EXISTS monitor_alert_rule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  metric VARCHAR(64) NOT NULL,
  operator VARCHAR(16) NOT NULL,
  threshold_value DECIMAL(18,4) NOT NULL,
  window_seconds INT NOT NULL DEFAULT 300,
  duration_seconds INT NOT NULL DEFAULT 0,
  level VARCHAR(16) NOT NULL DEFAULT 'warning',
  enabled TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_project_enabled (project_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规则';
