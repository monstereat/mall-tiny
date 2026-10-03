# Monitoring platform release workflow

`.github/workflows/monitor-release.yml` runs Java tests and reproducible SDK/Admin builds, then builds the Spring Boot server and Admin images and publishes both to GHCR when a `v*` tag is pushed. The pnpm workspace and Admin app use committed lockfiles with frozen installs. `workflow_dispatch` can build the selected ref; without a supplied image tag it uses the commit SHA. Every image also receives a short SHA tag.

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

The host must be x86-64 and have SSH, Docker Engine, and Docker Compose v2 installed. Prepare the project directory and its secured `.env` before running a deployment; the workflow never creates, copies, or modifies `.env`. Set non-empty, independently generated values for `MYSQL_ROOT_PASSWORD`, `CLICKHOUSE_PASSWORD`, `MINIO_ROOT_USER`, and `MINIO_ROOT_PASSWORD`; the deployment Compose file has no fallback credentials and fails validation if any are missing. Do not use `.env.example` values directly for a deployment. MySQL, Kafka, ClickHouse, and RustFS ports are reachable only on the Compose network; the Compose file does not publish them on host interfaces. Access them from the host through a controlled SSH tunnel when needed. The Compose stack must already be provisioned and its infrastructure services running. The workflow validates the Compose override, checks MySQL/ClickHouse connectivity, and pulls both application images before applying versioned migrations. It then updates only `server` and `admin` and waits up to five minutes for the Server actuator health endpoint, printing Server logs if it does not become healthy. The base deployment Compose file keeps the `monitor-platform` project, shared default network, and `com.monstereat.observability=true` labels.

Issue AI analysis remains disabled unless `MONITOR_AI_ENABLED=true` is set in the host environment or `.env`. When enabled, provide `OPENAI_API_KEY`; optionally set `MONITOR_AI_BASE_URL` and `MONITOR_AI_MODEL`. The key stays on the host and is passed only to the server container. `store=false` disables Responses application-state storage, but does not disable a provider's abuse-monitoring retention; review the selected provider's policy and organization-level retention controls before enabling AI for production data. OpenAI documents default abuse-monitoring retention of up to 30 days for API requests, subject to the organization's approved data controls: [OpenAI API data controls](https://developers.openai.com/api/docs/guides/your-data).

The workflow creates private GHCR packages unless their visibility is changed in GitHub package settings. Do not put credentials in this document or in the Compose files.
