#!/usr/bin/env bash
# Stops and removes the containers of the whole log2code stack (all profiles).
# Named volumes are kept (indexed data survives); this script never passes -v.
# Usage: infra/scripts/down.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(dirname "$SCRIPT_DIR")"

docker compose --profile '*' down
