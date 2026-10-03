# Monitoring platform release workflow

`.github/workflows/monitor-release.yml` runs the full Maven tests with both default and production Spring profiles, plus reproducible SDK/Admin builds, then builds the Spring Boot server and Admin images and publishes both to GHCR when a `v*` tag is pushed. The pnpm workspace and Admin app use committed lockfiles with frozen installs. Every publish uses an image tag derived from the full commit SHA, GitHub run ID, and run attempt; a supplied `release_tag` (or pushed Git tag) is stored as OCI version metadata, so retrying or reusing a release label cannot overwrite a deployed image tag.

Publishing does not deploy. Deployment runs only when a user manually starts the workflow and sets `deploy` to `true`. The job uses the GitHub Environment named `production`; configure required reviewers there if deployment approval is needed. Missing host settings or credentials make the job report a skipped deployment. Tag pushes never deploy automatically.

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

Issue AI analysis remains disabled unless `MONITOR_AI_ENABLED=true` is set in the host environment or `.env`. When enabled, provide `OPENAI_API_KEY`; optionally set `MONITOR_AI_BASE_URL` and `MONITOR_AI_MODEL`. The key stays on the host and is passed only to the server container. `store=false` disables Responses application-state storage, but does not disable a provider's abuse-monitoring retention; review the selected provider's policy and organization-level retention controls before enabling AI for production data. OpenAI documents default abuse-monitoring retention of up to 30 days for API requests, subject to the organization's approved data controls: [OpenAI API data controls](https://developers.openai.com/api/docs/guides/your-data).

Ingest rate limiting is configured by `MONITOR_RATE_LIMIT_PER_MINUTE` and defaults to `5000` events per minute per project. Set it in the deployment `.env` or host environment to override it; the deployment Compose passes it to the Server container. For local development, the base `docker-compose.yml` starts infrastructure only and Spring Boot runs on the host, so export the variable in the shell that starts Spring Boot.

The workflow creates private GHCR packages unless their visibility is changed in GitHub package settings. Do not put credentials in this document or in the Compose files.

## Local mocked deployment checks

The release workflow runs `python3 infra/release/tests/monitor-release-deploy-harness.py` during verification. You can run the same command from the repository root to exercise its embedded remote deployment script locally without starting Docker, opening SSH, contacting GHCR, or publishing images. The harness places a fake `docker` executable first on `PATH` and uses disposable files under the system temporary directory.

It verifies that already-applied MySQL and ClickHouse migration versions are skipped, the run-specific Docker auth directory is available for image pulls and removed on exit, and both application startup failure and a failed Server health window restore the previously running Server/Admin image references. These mocked checks validate script branching and cleanup; they do not validate a real Compose host, registry authentication, migrations against real MySQL/ClickHouse, or production rollback behavior.
