#!/usr/bin/env bash
# Gzips data/logs/*.log into datasets/<id>/logs/*.log.gz and writes datasets/<id>/manifest.yml (schema: 0.11
# in taskovi-dip.md). Reads dataset_id/oracle/petclinic_commit from data/logs/.session.json, written by
# session-start.sh (and stamped with stopped_at by session-stop.sh, which is not required to have run).
# Usage: infra/scripts/dataset-snapshot.sh <dataset_id> ["description"]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$INFRA_DIR")"
SESSION_FILE="$ROOT_DIR/data/logs/.session.json"

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
  echo "usage: $0 <dataset_id> [\"description\"]" >&2
  exit 1
fi
DATASET_ID="$1"
DESCRIPTION="${2:-}"

if [ ! -f "$SESSION_FILE" ]; then
  echo "$SESSION_FILE not found. Run session-start.sh (and session-stop.sh) first." >&2
  exit 1
fi

json_field() {
  sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" "$SESSION_FILE"
}

SESSION_DATASET_ID="$(json_field dataset_id)"
if [ "$SESSION_DATASET_ID" != "$DATASET_ID" ]; then
  echo "$SESSION_FILE belongs to dataset '$SESSION_DATASET_ID', not '$DATASET_ID'." >&2
  echo "Either run: $0 $SESSION_DATASET_ID [\"description\"], or start a new session for '$DATASET_ID' first." >&2
  exit 1
fi
ORACLE="$(json_field oracle)"
COMMIT="$(json_field petclinic_commit)"

OUT_DIR="$ROOT_DIR/datasets/$DATASET_ID"
if [ -e "$OUT_DIR" ]; then
  echo "$OUT_DIR already exists." >&2
  exit 1
fi
mkdir -p "$OUT_DIR/logs"

# service (== Fluent Bit tag / compose service name) -> Maven module, per taskovi-dip.md section 0.3.
SERVICES=(config-server discovery-server customers-service visits-service vets-service api-gateway)
module_of() {
  case "$1" in
    config-server) echo spring-petclinic-config-server ;;
    discovery-server) echo spring-petclinic-discovery-server ;;
    customers-service) echo spring-petclinic-customers-service ;;
    visits-service) echo spring-petclinic-visits-service ;;
    vets-service) echo spring-petclinic-vets-service ;;
    api-gateway) echo spring-petclinic-api-gateway ;;
  esac
}

FILES_YAML=""
TOTAL_LINES=0
printf '%-20s %8s  %6s\n' "service" "lines" "size"
for svc in "${SERVICES[@]}"; do
  src="$ROOT_DIR/data/logs/$svc.log"
  [ -f "$src" ] || continue
  lines="$(wc -l < "$src" | tr -d ' ')"
  gzip -c "$src" > "$OUT_DIR/logs/$svc.log.gz"
  size="$(du -h "$OUT_DIR/logs/$svc.log.gz" | cut -f1)"
  printf '%-20s %8s  %6s\n' "$svc" "$lines" "$size"
  TOTAL_LINES=$((TOTAL_LINES + lines))
  FILES_YAML="${FILES_YAML}  - path: logs/$svc.log.gz
    service: $svc
    module: $(module_of "$svc")
"
done

if [ -z "$FILES_YAML" ]; then
  rmdir "$OUT_DIR/logs" "$OUT_DIR" 2>/dev/null || true
  echo "No data/logs/*.log files found; nothing to snapshot. Did session-start.sh run and produce traffic?" >&2
  exit 1
fi
FILES_YAML="${FILES_YAML%$'\n'}"

CREATED_AT="$(date +%Y-%m-%dT%H:%M:%S%:z)"

cat > "$OUT_DIR/manifest.yml" <<YAML
dataset_id: $DATASET_ID
description: "$DESCRIPTION"
created_at: $CREATED_AT
oracle: $ORACLE
log_format: spring-boot-default
code:
  name: spring-petclinic-microservices
  version: $COMMIT
files:
$FILES_YAML
notes: ""
YAML

TOTAL_SIZE="$(du -sh "$OUT_DIR" | cut -f1)"
echo
echo "Wrote $OUT_DIR/manifest.yml"
echo "Total: $TOTAL_LINES lines across ${#SERVICES[@]} services, dataset size $TOTAL_SIZE"
