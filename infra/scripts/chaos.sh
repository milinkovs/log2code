#!/usr/bin/env bash
# Thin wrapper around one PetClinic service's Chaos Monkey actuator endpoints (customers, visits, vets only -
# the only 3 services with the "chaos-monkey" profile enabled, see infra/docker-compose.yml and ADR-004).
# JSON payload templates are copied, unmodified, from spring-petclinic-microservices/scripts/chaos/ into
# infra/chaos/. "on" enables the REST-controller watcher and the chosen assault (level 5: ~1 in 5 requests,
# non-deterministic); "off" disables both again.
# Usage: infra/scripts/chaos.sh <customers|visits|vets> <latency|exception|kill|memory> <on|off>
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(dirname "$SCRIPT_DIR")"
CHAOS_DIR="$INFRA_DIR/chaos"

usage() {
  echo "usage: $0 <customers|visits|vets> <latency|exception|kill|memory> <on|off>" >&2
  exit 1
}

[ $# -eq 3 ] || usage
SERVICE="$1"
ATTACK="$2"
STATE="$3"

case "$SERVICE" in
  customers) PORT=8081 ;;
  visits) PORT=8082 ;;
  vets) PORT=8083 ;;
  *) usage ;;
esac

case "$ATTACK" in
  latency) ASSAULT_FILE="attacks_enable_latency.json" ;;
  exception) ASSAULT_FILE="attacks_enable_exception.json" ;;
  kill) ASSAULT_FILE="attacks_enable_killapplication.json" ;;
  memory) ASSAULT_FILE="attacks_enable_memory.json" ;;
  *) usage ;;
esac

post() {
  local endpoint="$1" file="$2"
  curl -fsS "http://localhost:$PORT/actuator/chaosmonkey/$endpoint" \
    -H "Content-Type: application/json" --data "@$CHAOS_DIR/$file" >/dev/null
}

case "$STATE" in
  on)
    if [ "$ATTACK" = "kill" ]; then
      echo "WARNING: 'kill' terminates the $SERVICE-service JVM on the next matching request (no restart policy is configured)." >&2
    fi
    post assaults "$ASSAULT_FILE"
    post watchers watcher_enable_restcontroller.json
    echo "$SERVICE: $ATTACK chaos ON (REST-controller watcher, level 5 -> roughly 1 in 5 requests)"
    ;;
  off)
    post assaults attacks_disable.json
    post watchers watcher_disable.json
    echo "$SERVICE: chaos OFF"
    ;;
  *) usage ;;
esac
