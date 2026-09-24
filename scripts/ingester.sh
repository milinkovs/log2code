#!/usr/bin/env bash
# Runs the log2code-ingester CLI (T21). Usage: scripts/ingester.sh <command> [options...]
# Always runs from the log2code/ repo root, regardless of the caller's working directory,
# so relative paths (config/matching.yml, datasets/, data/work/ingest, ...) resolve correctly.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

exec java -Xmx"${INGESTER_XMX:-768m}" -jar log2code-ingester/target/log2code-ingester.jar "$@"
