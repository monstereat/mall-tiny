#!/usr/bin/env bash
set -euo pipefail

API_BASE="${API_BASE:-http://localhost:8080}"
PROJECT_KEY="${PROJECT_KEY:-demo-web}"
RELEASE_KEY="${RELEASE_KEY:-dev-release-key}"
VERSION="${VERSION:?VERSION is required}"
ENVIRONMENT="${ENVIRONMENT:-production}"
DIST_DIR="${DIST_DIR:-dist}"
GIT_COMMIT="${GIT_COMMIT:-$(git rev-parse HEAD 2>/dev/null || true)}"
BRANCH_NAME="${BRANCH_NAME:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)}"
NOW_MS="$(date +%s000)"

echo "Creating release ${PROJECT_KEY}@${VERSION}..."
curl --fail --silent --show-error   -X POST "${API_BASE}/api/v1/releases/${PROJECT_KEY}"   -H "Content-Type: application/json"   -H "X-Release-Key: ${RELEASE_KEY}"   -d "{
    \"version\": \"${VERSION}\",
    \"environment\": \"${ENVIRONMENT}\",
    \"gitCommit\": \"${GIT_COMMIT}\",
    \"branchName\": \"${BRANCH_NAME}\",
    \"buildTime\": ${NOW_MS},
    \"deployTime\": ${NOW_MS}
  }" >/dev/null

count=0
while IFS= read -r -d '' map_file; do
  bundle_path="${map_file%.map}"
  bundle_file="$(basename "${bundle_path}")"
  echo "Uploading SourceMap: ${bundle_file}"
  curl --fail --silent --show-error     -X POST "${API_BASE}/api/v1/releases/${PROJECT_KEY}/${VERSION}/sourcemaps"     -H "X-Release-Key: ${RELEASE_KEY}"     -F "environment=${ENVIRONMENT}"     -F "bundleFile=${bundle_file}"     -F "file=@${map_file};type=application/json" >/dev/null
  count=$((count + 1))
done < <(find "${DIST_DIR}" -type f -name '*.map' -print0)

echo "Uploaded ${count} SourceMap file(s)."
