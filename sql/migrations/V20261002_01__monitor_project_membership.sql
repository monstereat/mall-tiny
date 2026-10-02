-- Add project-scoped roles while preserving the existing mall-tiny RBAC gate.
-- Apply once after sql/monitor.sql; this script is safe to re-run.

CREATE TABLE IF NOT EXISTS monitor_schema_migration (
  version VARCHAR(64) NOT NULL,
  description VARCHAR(255) NOT NULL,
  installed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控平台数据库迁移记录';

CREATE TABLE IF NOT EXISTS monitor_project_member (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  admin_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_admin (project_id, admin_id),
  KEY idx_admin_project (admin_id, project_id),
  CONSTRAINT fk_monitor_member_project FOREIGN KEY (project_id)
    REFERENCES monitor_project (id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_member_admin FOREIGN KEY (admin_id)
    REFERENCES ums_admin (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控项目成员';

-- Existing monitor access is granted through mall-tiny RBAC resources.
-- Preserve access by granting all current holders of that resource OWNER.
INSERT IGNORE INTO monitor_project_member (project_id, admin_id, role)
SELECT p.id, a.id, 'OWNER'
FROM monitor_project p
JOIN ums_admin a ON a.status = 1
JOIN ums_admin_role_relation admin_role ON admin_role.admin_id = a.id
JOIN ums_role_resource_relation role_resource ON role_resource.role_id = admin_role.role_id
JOIN ums_resource resource ON resource.id = role_resource.resource_id
WHERE resource.url = '/monitor/admin/**';

-- Keep the legacy owner_id column populated for consumers that still read it.
UPDATE monitor_project p
JOIN (
  SELECT project_id, MIN(admin_id) AS admin_id
  FROM monitor_project_member
  WHERE role = 'OWNER'
  GROUP BY project_id
) owners ON owners.project_id = p.id
SET p.owner_id = owners.admin_id
WHERE p.owner_id IS NULL;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261002_01', 'monitor project membership and project-scoped access');
