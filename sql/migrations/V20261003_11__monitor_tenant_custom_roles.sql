CREATE TABLE IF NOT EXISTS monitor_tenant_role (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  role_key VARCHAR(64) NOT NULL,
  name VARCHAR(64) NOT NULL,
  permissions_json JSON NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_tenant_role_key (tenant_id, role_key),
  UNIQUE KEY uk_monitor_tenant_role_tenant_id (tenant_id, id),
  CONSTRAINT fk_monitor_tenant_role_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户自定义组织权限角色';

SET @monitor_member_role_column_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'monitor_tenant_member'
    AND column_name = 'custom_role_id'
);
SET @monitor_member_role_column_ddl = IF(@monitor_member_role_column_exists = 0,
  'ALTER TABLE monitor_tenant_member ADD COLUMN custom_role_id BIGINT DEFAULT NULL',
  'SELECT 1'
);
PREPARE monitor_member_role_column_stmt FROM @monitor_member_role_column_ddl;
EXECUTE monitor_member_role_column_stmt;
DEALLOCATE PREPARE monitor_member_role_column_stmt;

SET @monitor_member_role_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'monitor_tenant_member'
    AND index_name = 'idx_monitor_tenant_member_custom_role'
);
SET @monitor_member_role_index_ddl = IF(@monitor_member_role_index_exists = 0,
  'ALTER TABLE monitor_tenant_member ADD INDEX idx_monitor_tenant_member_custom_role (custom_role_id)',
  'SELECT 1'
);
PREPARE monitor_member_role_index_stmt FROM @monitor_member_role_index_ddl;
EXECUTE monitor_member_role_index_stmt;
DEALLOCATE PREPARE monitor_member_role_index_stmt;

SET @monitor_member_role_fk_exists = (
  SELECT COUNT(*) FROM information_schema.referential_constraints
  WHERE constraint_schema = DATABASE() AND constraint_name = 'fk_monitor_tenant_member_custom_role'
);
SET @monitor_member_role_fk_ddl = IF(@monitor_member_role_fk_exists = 0,
  'ALTER TABLE monitor_tenant_member ADD CONSTRAINT fk_monitor_tenant_member_custom_role FOREIGN KEY (tenant_id, custom_role_id) REFERENCES monitor_tenant_role (tenant_id, id) ON DELETE RESTRICT',
  'SELECT 1'
);
PREPARE monitor_member_role_fk_stmt FROM @monitor_member_role_fk_ddl;
EXECUTE monitor_member_role_fk_stmt;
DEALLOCATE PREPARE monitor_member_role_fk_stmt;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_11', 'tenant custom organizational roles');
