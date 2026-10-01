CREATE TABLE IF NOT EXISTS monitor_project (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  project_key VARCHAR(64) NOT NULL,
  ingest_key_hash CHAR(64) NOT NULL,
  release_key_hash CHAR(64) NOT NULL,
  platform VARCHAR(32) NOT NULL DEFAULT 'web',
  owner_id BIGINT DEFAULT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_key (project_key),
  UNIQUE KEY uk_ingest_key_hash (ingest_key_hash)
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
  cooldown_seconds INT NOT NULL DEFAULT 900,
  level VARCHAR(16) NOT NULL DEFAULT 'warning',
  webhook_url VARCHAR(1024) DEFAULT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_project_enabled (project_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规则';

INSERT INTO monitor_project (name, project_key, ingest_key_hash, release_key_hash, platform, status)
VALUES ('Demo Web', 'demo-web', 'eef52729e3d17f2433c7f16c4fb6f0f0f03abffc31b8af6ed4bd55c75ca6a35e', 'f9de03afc6d38be6a8e8127ea2735762113c7383bb10ac242774564a164d11bc', 'web', 1)
ON DUPLICATE KEY UPDATE name = VALUES(name), status = 1;

CREATE TABLE IF NOT EXISTS monitor_sourcemap (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  release_id BIGINT NOT NULL,
  bundle_file VARCHAR(512) NOT NULL,
  object_key VARCHAR(1024) NOT NULL,
  checksum CHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_release_bundle (release_id, bundle_file),
  KEY idx_project_release (project_id, release_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SourceMap 私有对象索引';

CREATE TABLE IF NOT EXISTS monitor_replay (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  session_id VARCHAR(128) NOT NULL,
  release_version VARCHAR(128) DEFAULT NULL,
  object_key VARCHAR(1024) NOT NULL,
  event_count INT NOT NULL DEFAULT 0,
  start_time DATETIME DEFAULT NULL,
  end_time DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_replay_event (event_id),
  KEY idx_project_session (project_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Session Replay 对象索引';

CREATE TABLE IF NOT EXISTS monitor_alert_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  rule_id BIGINT NOT NULL,
  metric VARCHAR(64) NOT NULL,
  metric_value DECIMAL(18,4) NOT NULL,
  threshold_value DECIMAL(18,4) NOT NULL,
  level VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'firing',
  fingerprint VARCHAR(128) DEFAULT NULL,
  message VARCHAR(512) NOT NULL,
  triggered_at DATETIME NOT NULL,
  recovered_at DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_project_triggered (project_id, triggered_at),
  KEY idx_rule_status (rule_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警触发记录';

