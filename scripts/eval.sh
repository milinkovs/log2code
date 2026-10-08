#!/usr/bin/env bash
# Runs the log2code-eval CLI (T33 run; T34 ablate, tune, validate). Usage: scripts/eval.sh <command> [options...]
# Always runs from the log2code/ repo root, regardless of the caller's working directory,
# so relative paths (docs/eval/, config/) resolve correctly.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

exec java -Xmx"${EVAL_XMX:-512m}" -jar log2code-eval/target/log2code-eval.jar "$@"
