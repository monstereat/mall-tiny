START TRANSACTION;

INSERT INTO ums_role (name, description, admin_count, create_time, status, sort)
SELECT 'Observability SCIM Member [monitor-scim-v1]',
       'monitor-scim-system-role:v1; tenant/project scoped', 0, NOW(), 1, 90
WHERE NOT EXISTS (
  SELECT 1 FROM ums_role
  WHERE description = 'monitor-scim-system-role:v1; tenant/project scoped'
);

SET @monitor_scim_member_role_id = (
  SELECT id FROM ums_role
  WHERE description = 'monitor-scim-system-role:v1; tenant/project scoped'
  ORDER BY id LIMIT 1
);

INSERT INTO ums_role_resource_relation (role_id, resource_id)
SELECT @monitor_scim_member_role_id, res.id
FROM ums_resource res
WHERE res.url = '/monitor/admin/**'
  AND NOT EXISTS (
    SELECT 1 FROM ums_role_resource_relation role_resource
    WHERE role_resource.role_id = @monitor_scim_member_role_id
      AND role_resource.resource_id = res.id
  )
LIMIT 1;

INSERT INTO ums_admin_role_relation (admin_id, role_id)
SELECT DISTINCT scim_user.admin_id, @monitor_scim_member_role_id
FROM monitor_scim_user scim_user
WHERE NOT EXISTS (
  SELECT 1 FROM ums_admin_role_relation current_role
  WHERE current_role.admin_id = scim_user.admin_id
    AND current_role.role_id = @monitor_scim_member_role_id
);

DELETE old_assignment
FROM ums_admin_role_relation old_assignment
JOIN ums_role old_role ON old_role.id = old_assignment.role_id
JOIN monitor_scim_user scim_user ON scim_user.admin_id = old_assignment.admin_id
WHERE old_role.name = 'Observability SCIM Member'
  AND old_role.description <> 'monitor-scim-system-role:v1; tenant/project scoped';

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_16', 'isolate the managed SCIM member role from similarly named custom roles');

COMMIT;
