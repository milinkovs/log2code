#!/usr/bin/env bash
# Starts a dataset-recording session: archives whatever is in data/logs, (re)starts PetClinic (normal or
# oracle mode) on top of the core stack, waits until it is healthy, and writes data/logs/.session.json with
# the metadata that session-stop.sh and dataset-snapshot.sh read.
# Usage: infra/scripts/session-start.sh <dataset_id> [--oracle]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$INFRA_DIR")"

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
  echo "usage: $0 <dataset_id> [--oracle]" >&2
  exit 1
fi
DATASET_ID="$1"
ORACLE=false
if [ "${2:-}" = "--oracle" ]; then
  ORACLE=true
elif [ -n "${2:-}" ]; then
  echo "usage: $0 <dataset_id> [--oracle]" >&2
  exit 1
fi

if [ -e "$ROOT_DIR/datasets/$DATASET_ID" ]; then
  echo "datasets/$DATASET_ID already exists. Pick another dataset_id or remove that folder first." >&2
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running. Start Docker Desktop and try again." >&2
  exit 1
fi

cd "$INFRA_DIR"

echo "Stopping any PetClinic services from a previous session..."
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
rm -f "$ROOT_DIR/data/logs/.session.json"

COMPOSE_FILES=(-f docker-compose.yml)
if [ "$ORACLE" = "true" ]; then
  COMPOSE_FILES+=(-f docker-compose.oracle.yml)
fi

echo "Starting core stack + PetClinic (oracle=$ORACLE)..."
docker compose "${COMPOSE_FILES[@]}" --profile petclinic up -d

wait_for() {
  local name="$1" timeout="$2"
  shift 2
  local waited=0
  echo -n "Waiting for $name "
  until "$@" >/dev/null 2>&1; do
    if [ "$waited" -ge "$timeout" ]; then
      echo "- timed out after ${timeout}s" >&2
      exit 1
    fi
    echo -n "."
    sleep 3
    waited=$((waited + 3))
  done
  echo " ok"
}

health_up() { curl -fs "http://localhost:$1/actuator/health" | grep -q '"status":"UP"'; }
routable() { curl -fs -o /dev/null "http://localhost:8080$1"; }
config_served() { curl -fs "http://localhost:8888/customers-service/docker" | grep -q '"propertySources":\[{'; }

wait_for "config-server    (:8888)" 180 config_served
wait_for "discovery-server (:8761)" 180 health_up 8761
wait_for "customers-service(:8081)" 240 health_up 8081
wait_for "visits-service   (:8082)" 240 health_up 8082
wait_for "vets-service     (:8083)" 240 health_up 8083
wait_for "api-gateway      (:8080)" 240 health_up 8080
wait_for "gateway route /api/customer/owners" 240 routable /api/customer/owners
wait_for "gateway route /api/vet/vets" 240 routable /api/vet/vets
wait_for "gateway route /api/visit/pets/visits" 240 routable "/api/visit/pets/visits?petId=1"

PETCLINIC_COMMIT="$(sed -n 's/^PETCLINIC_COMMIT=//p' "$INFRA_DIR/.env" | tail -n 1)"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

printf '{"dataset_id":"%s","oracle":%s,"started_at":"%s","petclinic_commit":"%s"}\n' \
  "$DATASET_ID" "$ORACLE" "$STARTED_AT" "$PETCLINIC_COMMIT" > "$ROOT_DIR/data/logs/.session.json"

echo
echo "Session '$DATASET_ID' started (oracle=$ORACLE)."
echo "Open http://localhost:8080 and run through the scenarios in docs/dataset-guide.md."
echo "When done: infra/scripts/session-stop.sh, then infra/scripts/dataset-snapshot.sh $DATASET_ID \"description\""
