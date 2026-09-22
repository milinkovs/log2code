#!/usr/bin/env bash
# Stops and removes only the PetClinic containers (profile "petclinic"). OpenSearch, Dashboards and Fluent Bit keep running.
# Named volumes are not touched. Also used before integration tests (-Pit) to free memory.
# Usage: infra/scripts/petclinic-down.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(dirname "$SCRIPT_DIR")"

docker compose --profile petclinic stop config-server discovery-server customers-service visits-service vets-service api-gateway
docker compose --profile petclinic rm -f config-server discovery-server customers-service visits-service vets-service api-gateway
