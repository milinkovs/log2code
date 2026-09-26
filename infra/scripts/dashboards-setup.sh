#!/usr/bin/env bash
# Imports the log2code saved objects into OpenSearch Dashboards (T32) and makes log2code-logs* the default
# index pattern. Idempotent: objects have fixed IDs and are imported with overwrite=true, so it can be run
# any number of times (e.g. after editing infra/dashboards/log2code.ndjson).
#   index pattern  log2code-logs*   (@timestamp; log_id is a URL link to http://localhost:8090/logs/{id})
#   saved search   "log2code – all logs"
#   visualization  "Match status by service"
# Usage: infra/scripts/dashboards-setup.sh        (DASHBOARDS_URL overrides http://localhost:5601)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(dirname "$SCRIPT_DIR")"
DASHBOARDS_URL="${DASHBOARDS_URL:-http://localhost:5601}"
NDJSON="log2code.ndjson"
INDEX_PATTERN_ID="log2code-logs"

wait_for_dashboards() {
  local waited=0 timeout=240
  echo -n "Waiting for Dashboards ($DASHBOARDS_URL) "
  until [ "$(curl -s -o /dev/null -w '%{http_code}' "$DASHBOARDS_URL/api/status")" = "200" ]; do
    if [ "$waited" -ge "$timeout" ]; then
      echo "- timed out after ${timeout}s (is the core stack up? infra/scripts/up.sh)" >&2
      exit 1
    fi
    echo -n "."
    sleep 3
    waited=$((waited + 3))
  done
  echo " ok"
}

wait_for_dashboards

# Relative file path: avoids MSYS path conversion of "@/c/..." in Git Bash on Windows.
cd "$INFRA_DIR/dashboards"
response="$(curl -s -X POST "$DASHBOARDS_URL/api/saved_objects/_import?overwrite=true" \
  -H 'osd-xsrf: true' --form "file=@$NDJSON")"
if ! grep -q '"success":true' <<<"$response"; then
  echo "Import failed: $response" >&2
  exit 1
fi
count="$(grep -o '"successCount":[0-9]*' <<<"$response" | cut -d: -f2)"
echo "Imported $count saved objects from infra/dashboards/$NDJSON"

response="$(curl -s -X POST "$DASHBOARDS_URL/api/opensearch-dashboards/settings" \
  -H 'osd-xsrf: true' -H 'Content-Type: application/json' \
  -d "{\"changes\":{\"defaultIndex\":\"$INDEX_PATTERN_ID\"}}")"
if ! grep -q "\"defaultIndex\":{\"userValue\":\"$INDEX_PATTERN_ID\"" <<<"$response"; then
  echo "Setting the default index pattern failed: $response" >&2
  exit 1
fi
echo "Default index pattern: log2code-logs*"
echo "Discover: $DASHBOARDS_URL/app/data-explorer/discover (saved search \"log2code – all logs\")"
