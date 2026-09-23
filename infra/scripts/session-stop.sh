#!/usr/bin/env bash
# Stops the PetClinic services of the current recording session and waits for Fluent Bit to flush its buffer,
# then records "stopped_at" in data/logs/.session.json. Core services (OpenSearch, Dashboards, Fluent Bit)
# keep running. Usage: infra/scripts/session-stop.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$INFRA_DIR")"
SESSION_FILE="$ROOT_DIR/data/logs/.session.json"

if [ ! -f "$SESSION_FILE" ]; then
  echo "$SESSION_FILE not found. Run session-start.sh first." >&2
  exit 1
fi

cd "$INFRA_DIR"
docker compose --profile petclinic stop \
  config-server discovery-server customers-service visits-service vets-service api-gateway
docker compose --profile petclinic rm -f \
  config-server discovery-server customers-service visits-service vets-service api-gateway >/dev/null

echo "Waiting 5s for Fluent Bit to flush its buffer..."
sleep 5

STOPPED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
sed -i "s/}\$/,\"stopped_at\":\"$STOPPED_AT\"}/" "$SESSION_FILE"

echo "Session stopped. Logs are in $ROOT_DIR/data/logs."
echo "Next: infra/scripts/dataset-snapshot.sh <dataset_id> \"description\""
