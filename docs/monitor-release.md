# Monitoring platform release workflow

`.github/workflows/monitor-release.yml` runs the full Maven tests with both default and production Spring profiles, plus reproducible SDK/Admin builds, then builds the Spring Boot server and Admin images and publishes both to GHCR when a `v*` tag is pushed. The pnpm workspace and Admin app use committed lockfiles with frozen installs. Every publish uses an image tag derived from the full commit SHA, GitHub run ID, and run attempt; a supplied `release_tag` (or pushed Git tag) is stored as OCI version metadata, so retrying or reusing a release label cannot overwrite a deployed image tag.

Publishing does not deploy. Deployment runs only when a user manually starts the workflow and sets `deploy` to `true`. The job uses the GitHub Environment named `production`; configure required reviewers there if deployment approval is needed. Missing host settings or credentials make the job report a skipped deployment. Tag pushes never deploy automatically.

## DingTalk alert notifications

Set `DINGTALK_WEBHOOK_URL`, `DINGTALK_SECRET` and `DINGTALK_TENANT_ID` in the server's local environment or secured `.env`. The deployment Compose file passes both to the server. Use the original robot URL containing `access_token`, without precomputed `timestamp` or `sign` parameters. Bind the configured robot to the intended organization ID using `DINGTALK_TENANT_ID`; missing or different tenant IDs permanently reject delivery to that robot. Configure a tenant notification route with that same URL and explicitly select it in the alert rule; environment configuration alone does not subscribe projects to notifications.

The outbox converts the official HTTPS `oapi.dingtalk.com/robot/send` endpoint to a text message containing project, rule, severity, metric, value, threshold, firing/resolved status, message and delivery ID. Each retry generates a fresh millisecond timestamp and HMAC-SHA256 signature. Only an integer `errcode=0` marks delivery successful; HTTP 200 business errors and malformed responses use the existing bounded retry policy. The environment signing secret is used only for the configured robot identity (official endpoint and decoded access token) in its bound tenant; URL case, query order and token encoding do not bypass this check. Other robot destinations are sent without this secret and can use keyword/IP security. Signing multiple robots with different secrets is not supported by this environment configuration. Generic Webhooks retain their existing JSON payload.

URL credentials remain hidden from members who only have `ALERT_ROUTE_READ`. Keep signing secrets out of route names, source control and logs. Request DNS/IP validation, fixed-IP connections, TLS host validation and redirect rejection also apply to DingTalk. See the [official custom robot documentation](https://open.dingtalk.com/document/orgapp/custom-robot-access). Request headers, body, signing fields and response checks were compared with the [Go Webhook reference implementation](https://github.com/lddsb/dingtalk-webhook/blob/master/webhook.go) and a [Python implementation](https://github.com/W1ndys/QFNUScoreReminder/blob/main/dingtalk.py).

## Deployment configuration

Configure these repository/environment variables and secrets on the `production` environment:

| Type | Name | Purpose |
| --- | --- | --- |
| Variable | `MONITOR_DEPLOY_HOST` | SSH host name or IP address |
| Variable | `MONITOR_DEPLOY_USER` | SSH account allowed to manage this Compose project |
| Variable | `MONITOR_DEPLOY_PATH` | Absolute path to the checked-out project, containing `docker-compose.deploy.yml` and the referenced `infra/` and `sql/` files |
| Variable | `MONITOR_DEPLOY_PORT` | Optional SSH port; defaults to `22` |
| Secret | `MONITOR_DEPLOY_SSH_PRIVATE_KEY` | Private key for the deployment account |
| Secret | `MONITOR_DEPLOY_KNOWN_HOSTS` | Pinned `known_hosts` entry for the deployment host |
| Secret | `GHCR_PULL_USERNAME` | Account that can pull the published packages |
| Secret | `GHCR_PULL_TOKEN` | Token with package read access on the host |

The host must be x86-64 and have SSH, Docker Engine, and Docker Compose v2 installed. Prepare the project directory and its secured `.env` before running a deployment; the workflow never creates, copies, or modifies `.env`. Set non-empty, independently generated values for `MYSQL_ROOT_PASSWORD`, `CLICKHOUSE_PASSWORD`, `MINIO_ROOT_USER`, and `MINIO_ROOT_PASSWORD`; the deployment Compose file has no fallback credentials and fails validation if any are missing. Do not use `.env.example` values directly for a deployment. MySQL, Kafka, ClickHouse, and RustFS ports are reachable only on the Compose network; the Compose file does not publish them on host interfaces. Access them from the host through a controlled SSH tunnel when needed. The Compose stack must already be provisioned and its infrastructure services running. The workflow validates the Compose override, checks MySQL/ClickHouse connectivity, and pulls both application images before applying pending versioned migrations. MySQL migration versions are read from `monitor_schema_migration`; ClickHouse versions are tracked in `monitor.schema_migration`. Applied versions are skipped. A failed migration may leave partial DDL without a version marker (MySQL DDL is not transactional); inspect the schema before retrying and repair with a forward migration when needed. Migration files must remain safe to replay until their version marker is written. The workflow then updates only `server` and `admin` and waits up to five minutes for the Server actuator health endpoint, printing Server logs if it does not become healthy. If the application update or health check fails, it attempts to restore the previously running server/admin images and checks Server health again, logging both services if recovery does not become healthy. This rollback covers application images only; database migrations remain applied and must be corrected with a forward migration. Remote GHCR credentials use a run-specific temporary Docker config; the remote and runner cleanup traps attempt to remove it. If SSH becomes unavailable before cleanup, the temporary remote config may remain and must be removed manually after host access returns. The base deployment Compose file keeps the `monitor-platform` project, shared default network, and `com.monstereat.observability=true` labels.

The deployment Compose file also reports the Admin container as healthy only after its Nginx root page responds. This confirms that the static console server is serving HTTP; it does not prove that the Admin-to-Server API path or user login works. Check both `server` and `admin` container health with `docker compose -f docker-compose.deploy.yml ps` after deployment.

Issue AI analysis remains disabled unless `MONITOR_AI_ENABLED=true` is set in the host environment or `.env`. Configure `MONITOR_AI_API_KEY`, `MONITOR_AI_BASE_URL`, and `MONITOR_AI_MODEL`; the older `OPENAI_API_KEY` remains a fallback. The key stays on the host and is passed only to the server container.

Structured diagnosis results must match the six-field response schema. The parser rejects duplicate fields, trailing JSON, incorrect field or array-element types, and non-finite or out-of-range confidence values; invalid provider results return a fixed 502 response without the raw provider body.

For manual DeepSeek provider acceptance, compile the synthetic service harness and generate the Maven test classpath:

```sh
docker run --rm -v "$PWD:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.11-eclipse-temurin-17 mvn -ntp -Dtest=MonitorIssueAiAnalysisServiceTest test
python3 infra/release/tests/monitor-issue-ai-provider-acceptance.py --from-local-server
```

This is an explicit network call to the configured DeepSeek provider; the harness only permits HTTPS api.deepseek.com. It uses the real analysis service and production HTTP configuration with mocked, synthetic Issue/SourceMap/telemetry sources; it does not access business data, authenticate through the product UI or run E2E. The harness checks approved evidence and redaction before sending, then validates the parsed result. The runner reads only AI settings from the local Server and prints fixed result records rather than credentials or response text. Without `--from-local-server`, it uses the three AI environment settings already supplied to the process. This manual harness is not discovered or run by the normal JUnit suite.

For DeepSeek, use `MONITOR_AI_BASE_URL=https://api.deepseek.com` and `MONITOR_AI_MODEL=deepseek-flash` (or another model supported by its Responses API). The current integration uses `POST /responses`, `instructions`, `input`, and JSON Schema output, which DeepSeek documents as supported. See [DeepSeek Responses API](https://api-docs.deepseek.com/guides/responses_api/) and its [API reference](https://api-docs.deepseek.com/api/create-response/). Keep the provider key in the host secret store or a local `.env` file, never in source control.

The application sends sanitized Issue evidence to the configured provider. A Responses `store=false` setting controls application-state storage where supported; it does not establish that provider-side safety logging or other processing is disabled. Review the selected provider's applicable API terms, retention controls, and data-location requirements before enabling AI for production data. OpenAI documents default abuse-monitoring retention of up to 30 days for API requests, subject to the organization's approved data controls: [OpenAI API data controls](https://developers.openai.com/api/docs/guides/your-data). DeepSeek documents that its Responses API is stateless and does not store response/conversation state: [DeepSeek Responses API](https://api-docs.deepseek.com/api/create-response/); confirm any separate provider logging and retention terms for the account being used.

Ingest rate limiting is configured by `MONITOR_RATE_LIMIT_PER_MINUTE` and defaults to `5000` events per minute per project. Set it in the deployment `.env` or host environment to override it; the deployment Compose passes it to the Server container. For local development, the base `docker-compose.yml` starts infrastructure only and Spring Boot runs on the host, so export the variable in the shell that starts Spring Boot.

The workflow creates private GHCR packages unless their visibility is changed in GitHub package settings. Do not put credentials in this document or in the Compose files.

## Local mocked deployment checks

The release workflow runs `python3 infra/release/tests/monitor-release-deploy-harness.py` during verification. You can run the same command from the repository root to exercise its embedded remote deployment script locally without starting Docker, opening SSH, contacting GHCR, or publishing images. The harness places a fake `docker` executable first on `PATH` and uses disposable files under the system temporary directory.

It verifies that already-applied MySQL and ClickHouse migration versions are skipped, the run-specific Docker auth directory is available for image pulls and removed on exit, and both application startup failure and a failed Server health window restore the previously running Server/Admin image references. These mocked checks validate script branching and cleanup; they do not validate a real Compose host, registry authentication, migrations against real MySQL/ClickHouse, or production rollback behavior.


## Numeric Explore storage acceptance

Explore supports `sum`, `avg`, `min` and `max` over `value` in Logs and mixed queries for up to 7 days, grouped by signal, environment, Release or level. Mixed numeric queries include numeric Performance/Metric events and JSON log messages; signals without numeric samples contribute no samples. Performance/Metric `data.value` must be a finite JSON number. Logs read the top-level `value` of the JSON message and also accept numeric strings convertible to a finite float. Missing, null, boolean, malformed and non-finite values are excluded; actual zero and negative values are included. Filter by the same metric and unit before combining measurements.

Average is computed from the combined sum and valid sample count. Source statistics use one query end time; sorting and the 100-row display limit are applied after grouping both sources. More than 1,000 groups, numeric/count overflow or Loki's own series limit returns 422. Invalid/partial Loki responses return a fixed 502. Loki-compatible filters are required for Logs/mixed numeric queries; unsupported Boolean expressions, negation or event-only fields are rejected. Saved queries enforce these same restrictions and can be used as existing Dashboard widgets. Percentiles remain available for individual Performance/Metric queries.

Run focused regression and compile the manual storage harness:

```sh
docker run --rm -v "$PWD:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.11-eclipse-temurin-17 mvn -ntp -Dtest=MonitorQueryExploreNumericTest,MonitorQueryServiceExploreFilterTest,MonitorExploreNumericAggregateControllerTest,MonitorExploreLogFilterTest,MonitorLogExploreNumericTest,MonitorLogQueryServiceTest,MonitorSavedExploreQueryServiceTest package
python3 infra/clickhouse/tests/explore-numeric-values.py
python3 infra/loki/tests/numeric-storage-acceptance.py
```

The ClickHouse probe is a read-only synthetic expression query against the local ClickHouse container. The Loki runner creates an isolated Loki 3.5.5 container and synthetic log storage, invokes the production Java query builder, and removes only its own container and temporary storage. It checks four grouping dimensions, invalid values, project/environment/user/tag filters and an empty result. These storage checks do not run product login, ingestion or browser E2E, and do not send business data to an external provider. Protocol references: [Loki metric queries](https://grafana.com/docs/loki/latest/query/metric_queries/) and [ClickHouse JSON functions](https://clickhouse.com/docs/reference/functions/regular-functions/json-functions).


## Replay quota reconciliation

Replay usage is tracked in the persistent project ledger. Successful deletion with a known object size decrements usage immediately. If the object size cannot be read but deletion succeeds, usage remains conservatively high until the next successful scheduled reconciliation; cleanup does not repeatedly enumerate the project prefix. This can temporarily reject uploads near the quota limit. First initialization, upload-failure recovery and scheduled reconciliation still enumerate objects to repair drift. Failed deletion does not decrement usage.
