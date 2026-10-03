CREATE TABLE IF NOT EXISTS monitor_alert_notification_route (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  webhook_url VARCHAR(1024) NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_alert_route_tenant_name (tenant_id, name),
  CONSTRAINT fk_monitor_alert_route_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户共享的告警通知路由';

SET @monitor_route_column_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'monitor_alert_rule'
    AND column_name = 'notification_route_id'
);
SET @monitor_route_column_ddl = IF(@monitor_route_column_exists = 0,
  'ALTER TABLE monitor_alert_rule ADD COLUMN notification_route_id BIGINT DEFAULT NULL',
  'SELECT 1'
);
PREPARE monitor_route_column_stmt FROM @monitor_route_column_ddl;
EXECUTE monitor_route_column_stmt;
DEALLOCATE PREPARE monitor_route_column_stmt;

SET @monitor_route_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'monitor_alert_rule'
    AND index_name = 'idx_alert_rule_notification_route'
);
SET @monitor_route_index_ddl = IF(@monitor_route_index_exists = 0,
  'ALTER TABLE monitor_alert_rule ADD INDEX idx_alert_rule_notification_route (notification_route_id)',
  'SELECT 1'
);
PREPARE monitor_route_index_stmt FROM @monitor_route_index_ddl;
EXECUTE monitor_route_index_stmt;
DEALLOCATE PREPARE monitor_route_index_stmt;

SET @monitor_route_fk_exists = (
  SELECT COUNT(*) FROM information_schema.referential_constraints
  WHERE constraint_schema = DATABASE() AND constraint_name = 'fk_monitor_alert_rule_notification_route'
);
SET @monitor_route_fk_ddl = IF(@monitor_route_fk_exists = 0,
  'ALTER TABLE monitor_alert_rule ADD CONSTRAINT fk_monitor_alert_rule_notification_route FOREIGN KEY (notification_route_id) REFERENCES monitor_alert_notification_route (id) ON DELETE RESTRICT',
  'SELECT 1'
);
PREPARE monitor_route_fk_stmt FROM @monitor_route_fk_ddl;
EXECUTE monitor_route_fk_stmt;
DEALLOCATE PREPARE monitor_route_fk_stmt;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_10', 'tenant-shared alert notification routes');
