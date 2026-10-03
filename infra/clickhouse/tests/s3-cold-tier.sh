#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
compose_file="$repo_root/infra/clickhouse/tests/s3-cold-compose.yml"
project="monitor-platform-s3-cold-e2e-$(date +%s)-$$"
export S3_COLD_RUN_ID="$project"
export S3_COLD_MINIO_USER="monitor"
export S3_COLD_MINIO_PASSWORD="$(openssl rand -hex 20)"
export S3_COLD_CLICKHOUSE_PASSWORD="$(openssl rand -hex 20)"

if docker ps -aq --filter "label=com.docker.compose.project=$project" | grep -q . \
  || docker volume ls -q --filter "label=com.docker.compose.project=$project" | grep -q . \
  || docker network ls -q --filter "label=com.docker.compose.project=$project" | grep -q .; then
  echo "Refusing to reuse existing Compose project: $project" >&2
  exit 1
fi

cleanup() {
  docker compose -p "$project" -f "$compose_file" down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker compose -p "$project" -f "$compose_file" up -d --wait

ch() {
  docker compose -p "$project" -f "$compose_file" exec -T clickhouse \
    sh -c 'clickhouse-client --user default --password "$CLICKHOUSE_PASSWORD" --multiquery "$@"' sh "$@"
}

ch --query "SELECT name FROM system.disks WHERE name = 's3_cold' FORMAT TabSeparated" | grep -qx s3_cold
ch --query "SELECT policy_name FROM system.storage_policies WHERE policy_name = 'monitor_hot_cold' FORMAT TabSeparated" | grep -qx monitor_hot_cold

ch --query "
CREATE TABLE monitor.cold_tier_probe
(
    event_time DateTime,
    probe_id UInt64,
    payload String
)
ENGINE = MergeTree
ORDER BY (event_time, probe_id)
TTL event_time + INTERVAL 5 SECOND TO VOLUME 'cold',
    event_time + INTERVAL 1 DAY DELETE
SETTINGS storage_policy = 'monitor_hot_cold';
INSERT INTO monitor.cold_tier_probe VALUES (now() - INTERVAL 1 MINUTE, 1, 's3-cold-roundtrip');
OPTIMIZE TABLE monitor.cold_tier_probe FINAL;
"

disk_name="$(ch --query "SELECT disk_name FROM system.parts WHERE database = 'monitor' AND table = 'cold_tier_probe' AND active FORMAT TabSeparated" | head -1)"
if [[ "$disk_name" != "s3_cold" ]]; then
  echo "Expected probe part on s3_cold, found: ${disk_name:-none}" >&2
  exit 1
fi

result="$(ch --query "SELECT payload FROM monitor.cold_tier_probe FORMAT TabSeparated")"
[[ "$result" == "s3-cold-roundtrip" ]]

docker compose -p "$project" -f "$compose_file" restart clickhouse >/dev/null
for attempt in $(seq 1 30); do
  if ch --query "SELECT 1" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
result="$(ch --query "SELECT payload FROM monitor.cold_tier_probe FORMAT TabSeparated")"
[[ "$result" == "s3-cold-roundtrip" ]]

run_deploy_migrations() {
  for migration in "$repo_root"/infra/clickhouse/migrations/*.sql; do
    docker compose -p "$project" -f "$compose_file" exec -T clickhouse \
      sh -c 'clickhouse-client --user default --password "$CLICKHOUSE_PASSWORD" --multiquery' < "$migration"
  done
}
run_deploy_migrations
run_deploy_migrations
default_policy_tables="$(ch --query "SELECT count() FROM system.tables WHERE database = 'monitor' AND name IN ('error_event', 'performance_event', 'metric_event', 'behavior_event', 'profile_event', 'replay_event', 'error_hourly', 'error_hourly_correction') AND storage_policy = 'default' AND create_table_query NOT LIKE '%TO VOLUME%' FORMAT TabSeparated")"
[[ "$default_policy_tables" == "8" ]]

docker compose -p "$project" -f "$compose_file" exec -T clickhouse \
  sh -c 'clickhouse-client --user default --password "$CLICKHOUSE_PASSWORD" --multiquery' \
  < "$repo_root/infra/clickhouse/optional/enable-s3-cold-tier.sql"
configured_tables="$(ch --query "SELECT count() FROM system.tables WHERE database = 'monitor' AND name IN ('error_event', 'performance_event', 'metric_event', 'behavior_event', 'profile_event', 'replay_event', 'error_hourly', 'error_hourly_correction') AND storage_policy = 'monitor_hot_cold' AND create_table_query LIKE '%TO VOLUME%' FORMAT TabSeparated")"
[[ "$configured_tables" == "8" ]]

echo "S3 cold tier passed: TTL moved the part to S3 and data remained readable after ClickHouse restart."
