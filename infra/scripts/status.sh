#!/usr/bin/env bash
# Prints cluster health, compose service state and memory usage of the log2code containers.
# Usage: infra/scripts/status.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(dirname "$SCRIPT_DIR")"

echo "== OpenSearch cluster health =="
curl -fs "http://localhost:9200/_cluster/health?pretty" || echo "OpenSearch is not reachable on localhost:9200"

echo
echo "== docker compose ps =="
docker compose --profile '*' ps

echo
echo "== docker stats (log2code containers) =="
ids="$(docker compose --profile '*' ps -q)"
if [ -z "$ids" ]; then
  echo "no running containers"
  exit 0
fi
# shellcheck disable=SC2086
docker stats --no-stream $ids

# Total of the "used" memory column, converted to MiB.
docker stats --no-stream --format '{{.MemUsage}}' $ids | awk '
  {
    v = $1; unit = v; sub(/^[0-9.]+/, "", unit); sub(/[A-Za-z]+$/, "", v)
    f = (unit == "GiB") ? 1024 : (unit == "MiB") ? 1 : (unit == "KiB") ? 1 / 1024 : (unit == "GB") ? 953.674 : (unit == "MB") ? 0.953674 : (unit == "kB") ? 0.000954 : 0
    total += v * f
  }
  END { printf "\nTotal memory in use: %.0f MiB (%.2f GiB)\n", total, total / 1024 }'
