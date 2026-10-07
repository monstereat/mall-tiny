CREATE TABLE IF NOT EXISTS monitor_tenant_audit_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  actor_admin_id BIGINT NOT NULL,
  action VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  detail_json JSON NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_monitor_tenant_audit_time (tenant_id, id),
  KEY idx_monitor_tenant_audit_actor (actor_admin_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控组织审计日志';

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261002_06', 'monitor tenant audit log');
