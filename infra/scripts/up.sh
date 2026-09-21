#!/usr/bin/env bash
# Starts the core stack (OpenSearch, Dashboards, Fluent Bit; no profiles) and waits until it is healthy.
# Usage: infra/scripts/up.sh
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
docker compose up -d

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

opensearch_ready() {
  curl -fs "http://localhost:9200/_cluster/health" | grep -Eq '"status":"(green|yellow)"'
}

dashboards_ready() {
  [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:5601/api/status)" = "200" ]
}

wait_for "OpenSearch (http://localhost:9200)" 180 opensearch_ready
wait_for "Dashboards (http://localhost:5601)" 240 dashboards_ready

echo "Stack is up. Fluent Bit listens on localhost:24224, logs go to $ROOT_DIR/data/logs"
