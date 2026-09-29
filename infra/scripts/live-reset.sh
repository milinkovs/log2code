#!/usr/bin/env bash
# Starts the "live" dataset over: stops the PetClinic services, archives data/logs/*.log to
# data/logs-archive/<UTC time>/, deletes every dataset_id=live document from log2code-logs and removes
# the follow offsets (data/work/ingest/live/offsets.json). Called by petclinic-up.sh and
# session-start.sh BEFORE PetClinic starts, so no new line can be ingested and then deleted.
# A running `scripts/ingester.sh follow` keeps working: it sees the files disappear and come back
# shorter, and starts them from line 1 (FileTailer reset).
# Usage: infra/scripts/live-reset.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$INFRA_DIR")"
OPENSEARCH_URL="${OPENSEARCH_URL:-http://localhost:9200}"

cd "$INFRA_DIR"

echo "Stopping any running PetClinic services..."
docker compose --profile petclinic stop \
  config-server discovery-server customers-service visits-service vets-service api-gateway 2>/dev/null || true
docker compose --profile petclinic rm -f \
  config-server discovery-server customers-service visits-service vets-service api-gateway >/dev/null 2>&1 || true

mkdir -p "$ROOT_DIR/data/logs"
if compgen -G "$ROOT_DIR/data/logs/*.log" >/dev/null; then
  ARCHIVE_DIR="$ROOT_DIR/data/logs-archive/$(date -u +%Y%m%dT%H%M%SZ)"
  mkdir -p "$ARCHIVE_DIR"
  mv "$ROOT_DIR"/data/logs/*.log "$ARCHIVE_DIR"/
  echo "Archived previous logs to $ARCHIVE_DIR"
fi
rm -f "$ROOT_DIR/data/work/ingest/live/offsets.json"

# The core stack has no profile; make sure OpenSearch is up before deleting from it.
docker compose up -d
echo -n "Waiting for OpenSearch ($OPENSEARCH_URL) "
waited=0
until curl -fs "$OPENSEARCH_URL/_cluster/health" | grep -Eq '"status":"(green|yellow)"'; do
  if [ "$waited" -ge 180 ]; then
    echo "- timed out after 180s" >&2
    exit 1
  fi
  echo -n "."
  sleep 3
  waited=$((waited + 3))
done
echo " ok"

# The index does not exist before the first ingest; then there is nothing to delete.
if [ "$(curl -s -o /dev/null -w '%{http_code}' "$OPENSEARCH_URL/log2code-logs")" = "200" ]; then
  response="$(curl -s -X POST "$OPENSEARCH_URL/log2code-logs/_delete_by_query?refresh=true&conflicts=proceed" \
    -H 'Content-Type: application/json' -d '{"query":{"term":{"dataset_id":"live"}}}')"
  deleted="$(grep -o '"deleted":[0-9]*' <<<"$response" | head -n 1 | cut -d: -f2)"
  if [ -z "$deleted" ]; then
    echo "Deleting the live dataset failed: $response" >&2
    exit 1
  fi
  echo "Deleted $deleted live log(s) from log2code-logs"
fi
echo "Live dataset reset."
