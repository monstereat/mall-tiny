CREATE TABLE IF NOT EXISTS monitor_alert_delivery (
  id VARCHAR(36) NOT NULL,
  project_id BIGINT NOT NULL,
  rule_id BIGINT NOT NULL,
  alert_record_id BIGINT NOT NULL,
  alert_status VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at BIGINT NOT NULL DEFAULT 0,
  claim_until BIGINT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  last_error VARCHAR(512) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_alert_delivery_project_created (project_id, created_at),
  KEY idx_alert_delivery_retry (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警通知投递持久记录';

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_12', 'persist alert notification delivery outbox');
