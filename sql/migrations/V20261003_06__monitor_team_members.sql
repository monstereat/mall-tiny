CREATE TABLE IF NOT EXISTS monitor_team_member (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  team_id BIGINT NOT NULL,
  admin_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_team_admin (team_id, admin_id),
  KEY idx_monitor_team_member_admin (admin_id, tenant_id),
  CONSTRAINT fk_monitor_team_member_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_team_member_team FOREIGN KEY (tenant_id, team_id)
    REFERENCES monitor_team (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_team_member_admin FOREIGN KEY (admin_id)
    REFERENCES ums_admin (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团队成员及团队项目访问角色';

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_06', 'monitor team memberships and team project access');
