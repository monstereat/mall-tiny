-- Introduce tenant isolation and team ownership for monitor projects.
-- Existing projects are assigned to the shared default tenant/team.
-- Apply after V20261002_01__monitor_project_membership.sql; safe to re-run.

CREATE TABLE IF NOT EXISTS monitor_schema_migration (
  version VARCHAR(64) NOT NULL,
  description VARCHAR(255) NOT NULL,
  installed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控平台数据库迁移记录';

CREATE TABLE IF NOT EXISTS monitor_tenant (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  tenant_key VARCHAR(64) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_key (tenant_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控租户';

CREATE TABLE IF NOT EXISTS monitor_tenant_member (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  admin_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_admin (tenant_id, admin_id),
  KEY idx_admin_tenant (admin_id, tenant_id),
  CONSTRAINT fk_monitor_tenant_member_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_tenant_member_admin FOREIGN KEY (admin_id)
    REFERENCES ums_admin (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控租户成员';

CREATE TABLE IF NOT EXISTS monitor_team (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  team_key VARCHAR(64) NOT NULL,
  is_default TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_team_key (tenant_id, team_key),
  UNIQUE KEY uk_team_tenant_id (tenant_id, id),
  KEY idx_team_tenant (tenant_id),
  CONSTRAINT fk_monitor_team_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控团队';

INSERT INTO monitor_tenant (name, tenant_key, status)
VALUES ('Default Tenant', 'default', 1)
ON DUPLICATE KEY UPDATE name = VALUES(name), status = 1;

INSERT INTO monitor_team (tenant_id, name, team_key, is_default)
SELECT id, 'Default Team', 'default', 1 FROM monitor_tenant WHERE tenant_key = 'default'
ON DUPLICATE KEY UPDATE name = VALUES(name), is_default = 1;

SET @has_tenant_id = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'monitor_project' AND column_name = 'tenant_id'
);
SET @sql = IF(@has_tenant_id = 0,
  'ALTER TABLE monitor_project ADD COLUMN tenant_id BIGINT NULL AFTER id',
  'SELECT 1'
);
PREPARE tenant_column_stmt FROM @sql;
EXECUTE tenant_column_stmt;
DEALLOCATE PREPARE tenant_column_stmt;

SET @has_team_id = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'monitor_project' AND column_name = 'team_id'
);
SET @sql = IF(@has_team_id = 0,
  'ALTER TABLE monitor_project ADD COLUMN team_id BIGINT NULL AFTER tenant_id',
  'SELECT 1'
);
PREPARE team_column_stmt FROM @sql;
EXECUTE team_column_stmt;
DEALLOCATE PREPARE team_column_stmt;

UPDATE monitor_project p
JOIN monitor_tenant t ON t.tenant_key = 'default'
JOIN monitor_team tm ON tm.tenant_id = t.id AND tm.team_key = 'default'
SET p.tenant_id = COALESCE(p.tenant_id, t.id),
    p.team_id = COALESCE(p.team_id, tm.id);

ALTER TABLE monitor_project
  MODIFY COLUMN tenant_id BIGINT NOT NULL,
  MODIFY COLUMN team_id BIGINT NOT NULL;

SET @has_project_tenant_team_index = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'monitor_project' AND index_name = 'idx_project_tenant_team'
);
SET @sql = IF(@has_project_tenant_team_index = 0,
  'ALTER TABLE monitor_project ADD KEY idx_project_tenant_team (tenant_id, team_id)',
  'SELECT 1'
);
PREPARE project_index_stmt FROM @sql;
EXECUTE project_index_stmt;
DEALLOCATE PREPARE project_index_stmt;

SET @has_project_team_fk = (
  SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE() AND table_name = 'monitor_project' AND constraint_name = 'fk_monitor_project_team'
);
SET @sql = IF(@has_project_team_fk = 0,
  'ALTER TABLE monitor_project ADD CONSTRAINT fk_monitor_project_team FOREIGN KEY (tenant_id, team_id) REFERENCES monitor_team (tenant_id, id)',
  'SELECT 1'
);
PREPARE project_team_fk_stmt FROM @sql;
EXECUTE project_team_fk_stmt;
DEALLOCATE PREPARE project_team_fk_stmt;

-- Preserve access and use the strongest existing project role within the default tenant.
INSERT IGNORE INTO monitor_tenant_member (tenant_id, admin_id, role)
SELECT t.id, pm.admin_id,
  CASE MAX(CASE pm.role WHEN 'OWNER' THEN 3 WHEN 'MEMBER' THEN 2 ELSE 1 END)
    WHEN 3 THEN 'OWNER'
    WHEN 2 THEN 'MEMBER'
    ELSE 'VIEWER'
  END
FROM monitor_project_member pm
JOIN monitor_project p ON p.id = pm.project_id
JOIN monitor_tenant t ON t.id = p.tenant_id
GROUP BY t.id, pm.admin_id;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261002_03', 'monitor tenant and team hierarchy');
