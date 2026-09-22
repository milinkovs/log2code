#!/usr/bin/env bash
# Starts the PetClinic services (compose profile "petclinic") on top of the core stack and waits until
# every service reports UP and the gateway can route to customers, vets and visits.
# Requires images built by infra/scripts/build-petclinic.sh (PETCLINIC_TAG in infra/.env).
# Usage: infra/scripts/petclinic-up.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$INFRA_DIR")"

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running. Start Docker Desktop and try again." >&2
  exit 1
fi

# Bind-mounted by the fluent-bit service; /data/ is git-ignored, so it may not exist yet.
mkdir -p "$ROOT_DIR/data/logs"

cd "$INFRA_DIR"
# The core services (opensearch, dashboards, fluent-bit) have no profile, so they are started too if they are not running.
docker compose --profile petclinic up -d

wait_for() {
  local name="$1" timeout="$2"; shift 2
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

health_up() {
  curl -fs "http://localhost:$1/actuator/health" | grep -q '"status":"UP"'
}

routable() {
  curl -fs -o /dev/null "http://localhost:8080$1"
}

# The config server has no actuator: /actuator/health would be read as configuration for an application called "actuator".
# It is ready when it can serve (and has cloned) the configuration of a real service.
config_served() {
  curl -fs "http://localhost:8888/customers-service/docker" | grep -q '"propertySources":\[{'
}

wait_for "config-server    (:8888)" 180 config_served
wait_for "discovery-server (:8761)" 180 health_up 8761
wait_for "customers-service(:8081)" 240 health_up 8081
wait_for "visits-service   (:8082)" 240 health_up 8082
wait_for "vets-service     (:8083)" 240 health_up 8083
wait_for "api-gateway      (:8080)" 240 health_up 8080

# The gateway resolves services through Eureka and refreshes its registry periodically, so routes may lag behind.
wait_for "gateway route /api/customer/owners" 240 routable /api/customer/owners
wait_for "gateway route /api/vet/vets" 240 routable /api/vet/vets
wait_for "gateway route /api/visit/pets/visits" 240 routable "/api/visit/pets/visits?petId=1"

echo "PetClinic is up (gateway http://localhost:8080). Logs go to $ROOT_DIR/data/logs"
