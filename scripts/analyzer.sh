#!/usr/bin/env bash
# Runs the log2code-analyzer CLI (T07). Usage: scripts/analyzer.sh <command> [options...]
# Always runs from the log2code/ repo root, regardless of the caller's working directory,
# so relative paths (config/analyzer.yml, data/work/analyzer, ...) resolve correctly.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

exec java -Xmx"${ANALYZER_XMX:-1024m}" -jar log2code-analyzer/target/log2code-analyzer.jar "$@"
