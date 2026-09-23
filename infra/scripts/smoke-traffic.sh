#!/usr/bin/env bash
# Generates a short burst of traffic through the PetClinic gateway: browsing (list/detail/unknown id/unknown
# route/vets/visits), a full write cycle (create+update owner, add+edit pet, add visit), invalid input (blank
# required fields -> 400), and one Chaos Monkey exception attack on customers-service around the midpoint.
# Requires a running session (infra/scripts/session-start.sh). Used to build the smoke-01/smoke-oracle-01
# datasets (T16 step 4), and reusable for ad hoc traffic during manual dataset recording (docs/dataset-guide.md).
# Usage: infra/scripts/smoke-traffic.sh [duration_seconds]   (default 120)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GW="http://localhost:8080"
DURATION="${1:-120}"
END=$((SECONDS + DURATION))
CHAOS_AT=$((SECONDS + DURATION / 2))
CHAOS_DONE=0

req() {
  curl -s -o /dev/null -w '%{http_code} ' "$@"
  return 0
}

extract_id() {
  sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p' | head -n1
}

browse() {
  req "$GW/api/customer/owners"
  req "$GW/api/customer/owners/$((RANDOM % 10 + 1))"
  req "$GW/api/customer/owners/9999"    # valid route, unknown id -> 200 with null body (docs/log-format.md section 8)
  req "$GW/api/nonexistent"             # unknown route -> 404
  req "$GW/api/vet/vets"
  req "$GW/api/visit/pets/visits?petId=$((RANDOM % 13 + 1))"
  echo
}

write_cycle() {
  local owner_body owner_id pet_body pet_id
  owner_body="$(curl -s -X POST "$GW/api/customer/owners" -H 'Content-Type: application/json' -d '{
    "firstName":"Smoke","lastName":"Tester","address":"1 Test St.","city":"Testville","telephone":"5551234567"
  }')"
  owner_id="$(printf '%s' "$owner_body" | extract_id)"
  [ -n "$owner_id" ] || { echo "(owner creation returned no id, skipping rest of write cycle)"; return 0; }

  req -X PUT "$GW/api/customer/owners/$owner_id" -H 'Content-Type: application/json' -d '{
    "firstName":"Smoke","lastName":"Tester","address":"2 Test St.","city":"Testville","telephone":"5559876543"
  }'

  pet_body="$(curl -s -X POST "$GW/api/customer/owners/$owner_id/pets" -H 'Content-Type: application/json' -d '{
    "id":0,"name":"Rex","birthDate":"2022-01-01","typeId":2
  }')"
  pet_id="$(printf '%s' "$pet_body" | extract_id)"
  if [ -n "$pet_id" ]; then
    req -X PUT "$GW/api/customer/owners/$owner_id/pets/$pet_id" -H 'Content-Type: application/json' -d "{
      \"id\":$pet_id,\"name\":\"Rex\",\"birthDate\":\"2022-01-01\",\"typeId\":2
    }"
    # Visits are served by visits-service (Path=/api/visit/**), not customers-service; the owner segment is a
    # wildcard in VisitResource ("owners/*/pets/{petId}/visits"), only petId is actually used.
    req -X POST "$GW/api/visit/owners/$owner_id/pets/$pet_id/visits" -H 'Content-Type: application/json' -d "{
      \"date\":\"$(date +%Y-%m-%d)\",\"description\":\"Smoke test visit\"
    }"
  fi

  # Invalid input: all required fields blank -> 400.
  req -X POST "$GW/api/customer/owners" -H 'Content-Type: application/json' -d '{
    "firstName":"","lastName":"","address":"","city":"","telephone":""
  }'
  echo
}

trigger_chaos() {
  echo "Triggering Chaos Monkey exception attack on customers-service..."
  "$SCRIPT_DIR/chaos.sh" customers exception on
  local i
  for i in 1 2 3 4 5 6 7 8; do
    req "$GW/api/customer/owners/$((RANDOM % 10 + 1))"
  done
  echo
  "$SCRIPT_DIR/chaos.sh" customers exception off
  CHAOS_DONE=1
}

echo "Generating ~${DURATION}s of traffic against $GW ..."
while [ "$SECONDS" -lt "$END" ]; do
  browse
  write_cycle
  if [ "$CHAOS_DONE" = 0 ] && [ "$SECONDS" -ge "$CHAOS_AT" ]; then
    trigger_chaos
  fi
done
[ "$CHAOS_DONE" = 1 ] || trigger_chaos

echo "Done."
