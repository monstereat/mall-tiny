#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
container="monitor-scim-migration-test-$$"
password='test-only-password'

cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run --rm -d --name "$container" --network none \
  -e MYSQL_ROOT_PASSWORD="$password" \
  mysql:8.4 >/dev/null

ready=0
for attempt in $(seq 1 60); do
  if docker exec -e MYSQL_PWD="$password" "$container" \
    mysql --user=root -e 'SELECT 1' >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
if [[ "$ready" != 1 ]]; then
  echo 'Temporary MySQL did not become ready.' >&2
  exit 1
fi

mysql() {
  docker exec -i -e MYSQL_PWD="$password" "$container" \
    mysql --user=root monitor_migration_test "$@"
}

docker exec -e MYSQL_PWD="$password" "$container" \
  mysql --user=root -e 'CREATE DATABASE monitor_migration_test'
mysql <<'SQL'
CREATE TABLE monitor_schema_migration (
  version VARCHAR(64) NOT NULL PRIMARY KEY,
  description VARCHAR(255) NOT NULL,
  installed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE ums_role (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  description VARCHAR(255),
  admin_count INT NOT NULL DEFAULT 0,
  create_time DATETIME,
  status TINYINT,
  sort INT
);
CREATE TABLE ums_resource (id BIGINT NOT NULL PRIMARY KEY, url VARCHAR(255) NOT NULL);
CREATE TABLE ums_role_resource_relation (role_id BIGINT NOT NULL, resource_id BIGINT NOT NULL);
CREATE TABLE ums_admin_role_relation (admin_id BIGINT NOT NULL, role_id BIGINT NOT NULL);
CREATE TABLE monitor_scim_user (admin_id BIGINT NOT NULL);

INSERT INTO monitor_schema_migration (version, description)
VALUES ('20261003_07', 'monitor SCIM credentials and provisioned identities');
INSERT INTO ums_role (id, name, description) VALUES (1, 'Observability SCIM Member', 'legacy role');
INSERT INTO ums_resource (id, url) VALUES (10, '/monitor/admin/**');
INSERT INTO ums_admin_role_relation (admin_id, role_id) VALUES (42, 1);
INSERT INTO monitor_scim_user (admin_id) VALUES (42);
SQL

migration="$repo_root/sql/migrations/V20261003_16__monitor_scim_role_isolation.sql"
mysql < "$migration"
old_assignment_count="$(mysql -N -e 'SELECT COUNT(*) FROM ums_admin_role_relation WHERE admin_id = 42 AND role_id = 1')"
[[ "$old_assignment_count" == 0 ]]

# A later administrator action may assign the same legacy-named role again.
mysql -e 'INSERT INTO ums_admin_role_relation (admin_id, role_id) VALUES (42, 1)'
mysql < "$migration"
old_assignment_count="$(mysql -N -e 'SELECT COUNT(*) FROM ums_admin_role_relation WHERE admin_id = 42 AND role_id = 1')"
migration_record_count="$(mysql -N -e "SELECT COUNT(*) FROM monitor_schema_migration WHERE version = '20261003_16'")"
[[ "$old_assignment_count" == 1 ]]
[[ "$migration_record_count" == 1 ]]

echo 'SCIM role isolation migration replay test passed.'
