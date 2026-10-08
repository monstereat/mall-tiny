CREATE TABLE IF NOT EXISTS monitor_scim_token (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_by BIGINT NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_used_at DATETIME(3) DEFAULT NULL,
  revoked_at DATETIME(3) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_scim_token_hash (token_hash),
  KEY idx_monitor_scim_token_tenant (tenant_id, revoked_at),
  CONSTRAINT fk_monitor_scim_token_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_scim_token_creator FOREIGN KEY (created_by)
    REFERENCES ums_admin (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SCIM 租户级凭据，仅保存 SHA-256 摘要';

CREATE TABLE IF NOT EXISTS monitor_scim_user (
  scim_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id BIGINT NOT NULL,
  admin_id BIGINT NOT NULL,
  external_id VARCHAR(256) DEFAULT NULL,
  active TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (scim_id),
  UNIQUE KEY uk_monitor_scim_user_tenant_admin (tenant_id, admin_id),
  UNIQUE KEY uk_monitor_scim_user_tenant_external (tenant_id, external_id),
  KEY idx_monitor_scim_user_tenant_active (tenant_id, active),
  CONSTRAINT fk_monitor_scim_user_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_scim_user_admin FOREIGN KEY (admin_id)
    REFERENCES ums_admin (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SCIM 用户与 mall-tiny 管理员映射';

CREATE TABLE IF NOT EXISTS monitor_scim_group (
  scim_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id BIGINT NOT NULL,
  team_id BIGINT NOT NULL,
  external_id VARCHAR(256) DEFAULT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (scim_id),
  UNIQUE KEY uk_monitor_scim_group_tenant_team (tenant_id, team_id),
  UNIQUE KEY uk_monitor_scim_group_tenant_external (tenant_id, external_id),
  CONSTRAINT fk_monitor_scim_group_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_scim_group_team FOREIGN KEY (tenant_id, team_id)
    REFERENCES monitor_team (tenant_id, id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SCIM Group 与监控团队映射';

INSERT INTO ums_role (name, description, admin_count, create_time, status, sort)
SELECT 'Observability SCIM Member [monitor-scim-v1]', 'monitor-scim-system-role:v1; tenant/project scoped', 0, NOW(), 1, 90
WHERE NOT EXISTS (SELECT 1 FROM ums_role WHERE description = 'monitor-scim-system-role:v1; tenant/project scoped');

SET @monitor_scim_member_role_id = (
  SELECT id FROM ums_role WHERE description = 'monitor-scim-system-role:v1; tenant/project scoped' ORDER BY id LIMIT 1
);
INSERT INTO ums_role_resource_relation (role_id, resource_id)
SELECT @monitor_scim_member_role_id, res.id
FROM ums_resource res
WHERE res.url = '/monitor/admin/**'
  AND NOT EXISTS (
    SELECT 1 FROM ums_role_resource_relation role_resource
    WHERE role_resource.role_id = @monitor_scim_member_role_id AND role_resource.resource_id = res.id
  )
LIMIT 1;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_07', 'monitor SCIM credentials and provisioned identities');
