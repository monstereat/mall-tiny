#!/usr/bin/env bash
set -euo pipefail

compose_file="${COMPOSE_FILE:-docker-compose.deploy.yml}"
base_url="${MONITOR_BASE_URL:-http://localhost:8080}"
health_url="${MONITOR_HEALTH_URL:-http://localhost:8081/actuator/health}"
minio_health_url="${MINIO_HEALTH_URL:-}"
jaeger_query_url="${JAEGER_QUERY_URL:-http://localhost:16686}"
ingest_key="${MONITOR_INGEST_KEY:-dev-monitor-key}"
clickhouse_password="${CLICKHOUSE_PASSWORD:-}"
admin_user="${MONITOR_ADMIN_USER:-admin}"
admin_password="${MONITOR_ADMIN_PASSWORD:-macro123}"
run_id="${GITHUB_RUN_ID:-local}-$(date +%s)-$$"
alert_rule_id=""
alert_rule_name=""
alert_rule_metric=""
alert_rule_threshold=0
alert_rule_window=60
alert_rule_duration=0
alert_rule_cooldown=0
alert_rule_level="critical"

compose() {
  docker compose -f "$compose_file" "$@"
}

if [[ -z "$clickhouse_password" ]]; then
  clickhouse_password="$(compose config --format json | jq -er '.services.clickhouse.environment.CLICKHOUSE_PASSWORD')"
fi

wait_for_server() {
  local attempt
  for attempt in $(seq 1 120); do
    if curl --silent --fail "$health_url" >/dev/null; then
      return 0
    fi
    sleep 2
  done
  echo "Spring Boot did not become healthy: $health_url" >&2
  compose logs --tail=100 server >&2 || true
  return 1
}

wait_for_minio() {
  local attempt
  for attempt in $(seq 1 120); do
    if [[ -n "$minio_health_url" ]]; then
      if curl --silent --fail "$minio_health_url" >/dev/null; then
        return 0
      fi
    elif compose exec -T minio curl --fail --silent http://127.0.0.1:9000/health/ready >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "MinIO did not become ready: $minio_health_url" >&2
  compose logs --tail=100 minio >&2 || true
  return 1
}

send_event() {
  local event_id="$1"
  local event_type="$2"
  local data="$3"
  local traceparent="${4:-}"
  local session_id="e2e-session-$run_id"
  local payload
  payload=$(cat <<JSON
{"eventId":"$event_id","projectId":"demo-web","eventType":"$event_type","timestamp":$(date +%s)000,"sessionId":"$session_id","userId":"e2e-user","release":"e2e-$run_id","environment":"e2e","pageUrl":"http://localhost:5174/e2e","sdkVersion":"e2e-smoke","device":{},"data":$data}
JSON
)
  if [[ -n "$traceparent" ]]; then
    local response response_traceparent response_trace_id expected_trace_id response_body
    response=$(curl --silent --show-error --fail-with-body --include \
      -H 'Content-Type: application/json' \
      -H "X-Monitor-Key: $ingest_key" \
      -H "traceparent: $traceparent" \
      --data "$payload" \
      "$base_url/api/v1/envelope")
    response_traceparent=$(awk 'tolower($1) == "traceparent:" { value = $2 } END { print value }' <<<"$response" | tr -d '\r')
    expected_trace_id="${traceparent#*-}"
    expected_trace_id="${expected_trace_id%%-*}"
    response_trace_id="${response_traceparent#*-}"
    response_trace_id="${response_trace_id%%-*}"
    if [[ "$response_trace_id" != "$expected_trace_id" ]]; then
      echo "Ingest response traceparent did not preserve traceId: expected=$expected_trace_id actual=$response_traceparent" >&2
      return 1
    fi
    response_body="${response#*$'\r\n\r\n'}"
    jq -e '.data.accepted == true' <<<"$response_body" >/dev/null
    return 0
  fi

  curl --silent --show-error --fail-with-body \
    -H 'Content-Type: application/json' \
    -H "X-Monitor-Key: $ingest_key" \
    --data "$payload" \
    "$base_url/api/v1/envelope" >/dev/null
}

wait_for_event() {
  local table="$1"
  local event_id="$2"
  local attempt count
  for attempt in $(seq 1 60); do
    count=$(compose exec -T clickhouse clickhouse-client \
      --user default \
      --password "$clickhouse_password" \
      --query "SELECT count() FROM monitor.$table WHERE event_id = '$event_id'")
    if [[ "$count" -gt 0 ]]; then
      return 0
    fi
    sleep 2
  done
  echo "Event was not written to ClickHouse: table=$table eventId=$event_id" >&2
  compose logs --tail=100 server kafka >&2 || true
  return 1
}

wait_for_trace_parent_chain() {
  local trace_id="$1"
  local request_parent_span_id="$2"
  local timeout_seconds=60
  local deadline=$(( $(date +%s) + timeout_seconds ))
  local remaining_seconds trace

  while (( $(date +%s) < deadline )); do
    remaining_seconds=$(( deadline - $(date +%s) ))
    if ! trace=$(curl --silent --show-error --max-time "$remaining_seconds" \
      "$jaeger_query_url/api/traces/$trace_id"); then
      sleep 1
      continue
    fi
    if jq -e \
      --arg traceId "$trace_id" \
      --arg requestParentSpanId "$request_parent_span_id" \
      'def parent_span_id($span):
         [$span.references[]? | select(.refType == "CHILD_OF") | .spanID] | first // null;
       def descends_from($spans; $span; $ancestorSpanId):
         parent_span_id($span) as $parentSpanId |
         if $parentSpanId == $ancestorSpanId then true
         elif $parentSpanId == null then false
         else ([ $spans[] | select(.spanID == $parentSpanId) ] | first) as $parentSpan |
           if $parentSpan == null then false
           else descends_from($spans; $parentSpan; $ancestorSpanId)
           end
         end;
       .data[]? | select(.traceID == $traceId) | .spans as $spans |
        [ $spans[] as $http |
          select($http.operationName == "http post /api/v1/envelope" and
            any($http.references[]?; .refType == "CHILD_OF" and .spanID == $requestParentSpanId)) |
          $spans[] as $producer |
          select($producer.operationName == "monitor-performance-v1 send" and
            descends_from($spans; $producer; $http.spanID)) |
          $spans[] |
          select(.operationName == "monitor.kafka.consume" and
            any(.references[]?; .refType == "CHILD_OF" and .spanID == $producer.spanID))
        ] | length > 0' <<<"$trace" >/dev/null; then
      return 0
    fi
    sleep 1
  done

  echo "Jaeger trace did not contain the expected HTTP -> Kafka producer -> consumer CHILD_OF chain: traceId=$trace_id" >&2
  return 1
}

wait_for_alert_status() {
  local rule_id="$1"
  local expected_status="$2"
  local timeout_seconds=90
  local deadline=$(( $(date +%s) + timeout_seconds ))
  local records

  while (( $(date +%s) < deadline )); do
    records=$(curl --silent --show-error --fail-with-body \
      -H "Authorization: Bearer $token" \
      "$base_url/monitor/admin/demo-web/alerts/records")
    if jq -e --argjson ruleId "$rule_id" --arg status "$expected_status" \
      '[.data[]? | select(.ruleId == $ruleId and .status == $status)] | length > 0' \
      <<<"$records" >/dev/null; then
      return 0
    fi
    sleep 1
  done

  echo "Alert did not reach status=$expected_status for ruleId=$rule_id" >&2
  return 1
}

disable_alert_rule() {
  if [[ -z "$alert_rule_id" ]]; then
    return
  fi
  curl --silent --show-error --fail-with-body \
    -X PUT \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $token" \
    --data "{\"name\":\"$alert_rule_name\",\"metric\":\"$alert_rule_metric\",\"operator\":\">\",\"thresholdValue\":$alert_rule_threshold,\"windowSeconds\":$alert_rule_window,\"durationSeconds\":$alert_rule_duration,\"cooldownSeconds\":$alert_rule_cooldown,\"level\":\"$alert_rule_level\",\"enabled\":0}" \
    "$base_url/monitor/admin/demo-web/alerts/rules/$alert_rule_id" >/dev/null
}

wait_for_server
wait_for_minio

if compose config --services | grep -qx e2e-alert-receiver; then
  compose exec -T e2e-alert-receiver python /app/verify.py
fi

error_id="e2e-error-$run_id"
performance_id="e2e-performance-$run_id"
behavior_id="e2e-behavior-$run_id"
replay_id="e2e-replay-$run_id"
trace_id=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
trace_parent_span_id=$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')
traceparent="00-$trace_id-$trace_parent_span_id-01"

send_event "$error_id" ERROR '{"name":"E2EError","message":"smoke test error","stack":"Error: smoke test error\\n at e2e (http://localhost:5174/e2e.js:1:1)"}'
send_event "$performance_id" PERFORMANCE '{"metric":"LCP","value":1234}' "$traceparent"
send_event "$behavior_id" BEHAVIOR '{"category":"api","url":"/e2e","method":"GET","status":200,"duration":12}'
send_event "$replay_id" REPLAY '{"format":"rrweb","events":[{"type":4,"timestamp":1,"data":{"href":"http://localhost:5174/e2e","width":1280,"height":720}}]}'

wait_for_event error_event "$error_id"
wait_for_event performance_event "$performance_id"
wait_for_trace_parent_chain "$trace_id" "$trace_parent_span_id"
wait_for_event behavior_event "$behavior_id"
wait_for_event replay_event "$replay_id"

token=$(curl --silent --show-error --fail-with-body \
  -H 'Content-Type: application/json' \
  --data "{\"username\":\"$admin_user\",\"password\":\"$admin_password\"}" \
  "$base_url/admin/login" | jq -er '.data.token')
trap disable_alert_rule EXIT

alert_rule_name="e2e-api-failure-alert-$run_id"
alert_rule_metric="api_failure_count"
alert_rule_threshold=0
alert_rule_window=60
alert_rule_duration=0
alert_rule_cooldown=0
alert_rule=$(curl --silent --show-error --fail-with-body \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $token" \
  --data "{\"name\":\"$alert_rule_name\",\"metric\":\"$alert_rule_metric\",\"operator\":\">\",\"thresholdValue\":$alert_rule_threshold,\"windowSeconds\":$alert_rule_window,\"durationSeconds\":$alert_rule_duration,\"cooldownSeconds\":$alert_rule_cooldown,\"level\":\"$alert_rule_level\",\"enabled\":1}" \
  "$base_url/monitor/admin/demo-web/alerts/rules")
alert_rule_id=$(jq -er '.data.id' <<<"$alert_rule")

api_failure_id="e2e-api-failure-$run_id"
send_event "$api_failure_id" BEHAVIOR '{"category":"api","url":"/e2e/failure","method":"GET","status":503,"duration":12}'
wait_for_event behavior_event "$api_failure_id"
wait_for_alert_status "$alert_rule_id" firing
wait_for_alert_status "$alert_rule_id" resolved
disable_alert_rule
alert_rule_id=""

alert_rule_name="e2e-lcp-alert-$run_id"
alert_rule_metric="LCP"
alert_rule_threshold=1000
alert_rule_window=60
alert_rule_duration=0
alert_rule_cooldown=0
alert_rule=$(curl --silent --show-error --fail-with-body \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $token" \
  --data "{\"name\":\"e2e-lcp-alert-$run_id\",\"metric\":\"LCP\",\"operator\":\">\",\"thresholdValue\":1000,\"windowSeconds\":60,\"durationSeconds\":0,\"cooldownSeconds\":0,\"level\":\"critical\",\"enabled\":1}" \
  "$base_url/monitor/admin/demo-web/alerts/rules")
alert_rule_id=$(jq -er '.data.id' <<<"$alert_rule")

alert_high_id="e2e-alert-lcp-high-$run_id"
alert_low_id="e2e-alert-lcp-low-$run_id"
send_event "$alert_high_id" PERFORMANCE '{"metric":"LCP","value":1500}'
wait_for_alert_status "$alert_rule_id" firing

send_event "$alert_low_id" PERFORMANCE '{"metric":"LCP","value":50}'
wait_for_alert_status "$alert_rule_id" resolved

dashboard_ok=false
for attempt in $(seq 1 30); do
  dashboard=$(curl --silent --show-error --fail-with-body \
    -H "Authorization: Bearer $token" \
    "$base_url/monitor/admin/demo-web/dashboard?hours=1&environment=e2e&release=e2e-$run_id")
  if [[ "$(jq -r '.data.errorCount // 0' <<<"$dashboard")" -gt 0 ]]; then
    dashboard_ok=true
    break
  fi
  sleep 2
done
if [[ "$dashboard_ok" != true ]]; then
  echo "Admin dashboard did not return the ingested error event" >&2
  exit 1
fi

replays=$(curl --silent --show-error --fail-with-body \
  -H "Authorization: Bearer $token" \
  "$base_url/monitor/admin/demo-web/replays?sessionId=e2e-session-$run_id")
jq -e '.data | length > 0' <<<"$replays" >/dev/null

disable_alert_rule
alert_rule_id=""
echo "Infrastructure, API failure, and LCP alert E2E smoke passed for run $run_id (alert rules disabled; alert records retained)"
