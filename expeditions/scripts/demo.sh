#!/usr/bin/env sh
# Walks one party through phase 1 against the compose stack: queue, admission, holds, a failed ready check and its
# compensation, then a locked party. Needs curl and jq.
#   READY_CHECK=PT15S docker compose -f expeditions/docker-compose.yml up --build -d
#   sh expeditions/scripts/demo.sh
set -eu

CATALOG=${CATALOG:-http://localhost:8081}
QUEUE=${QUEUE:-http://localhost:8082}
RESERVATION=${RESERVATION:-http://localhost:8083}
RUN=$(date +%s)

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
as() { who=$1; shift; curl -sf -H "X-Adventurer: $who-$RUN" "$@"; }

EXP=$(curl -sf "$CATALOG/api/expeditions" | jq -r '.[0].id')
say "Expedition $EXP: $(curl -sf "$CATALOG/api/expeditions/$EXP" | jq -r '"\(.name), \(.totalSlots) slots, opens \(.opensAt)"')"

say "Four adventurers line up"
for who in tank healer dps1 dps2; do
  as $who -X POST "$QUEUE/api/expeditions/$EXP/queue" | jq -c "{who: \"$who\", state, position, opensAt}"
done

admitted() {
  while :; do
    s=$(as "$1" "$QUEUE/api/expeditions/$EXP/queue")
    [ "$(echo "$s" | jq -r .state)" = ADMITTED ] && { echo "$s" | jq -r .admissionToken; return; }
    sleep 2
  done
}

hold() { # who role -> reservation id
  token=$(admitted "$1")
  as "$1" -X POST "$RESERVATION/api/expeditions/$EXP/reservations" -H 'Content-Type: application/json' \
    -d "{\"role\":\"$2\",\"admissionToken\":\"$token\"}" | jq -r .id
}

say "Waiting for admission, then holding a role each"
TANK=$(hold tank TANK); HEALER=$(hold healer HEALER); DPS1=$(hold dps1 DAMAGE); DPS2=$(hold dps2 DAMAGE)
as tank "$RESERVATION/api/reservations/$TANK" | jq -c '{party: .party.number, status: .party.status, readyBy: .party.readyBy, members: [.party.members[] | "\(.role):\(.status)"]}'

say "Ready check: three confirm, dps2 doesn't answer"
for pair in "tank $TANK" "healer $HEALER" "dps1 $DPS1"; do
  set -- $pair; as "$1" -X POST "$RESERVATION/api/reservations/$2/confirm" | jq -c "{who: \"$1\", status}"
done

say "Waiting for the ready check to run out..."
while [ "$(as tank "$RESERVATION/api/reservations/$TANK" | jq -r .party.status)" = ASSEMBLED ]; do sleep 2; done
as dps2 "$RESERVATION/api/reservations/$DPS2" | jq -c '{who: "dps2", status}'
as tank "$RESERVATION/api/reservations/$TANK" | jq -c '{who: "tank", status, heldUntil, party: .party.status}'

say "dps2 lines up again, gets the reopened slot, and everyone confirms"
as dps2 -X POST "$QUEUE/api/expeditions/$EXP/queue" | jq -c '{who: "dps2", state, position}'
DPS2=$(hold dps2 DAMAGE)
for pair in "tank $TANK" "healer $HEALER" "dps1 $DPS1" "dps2 $DPS2"; do
  set -- $pair; as "$1" -X POST "$RESERVATION/api/reservations/$2/confirm" | jq -c "{who: \"$1\", status, party: .party.status}"
done

say "Capacity"
curl -sf "$RESERVATION/api/expeditions/$EXP/capacity" | jq -c .
echo "Events: http://localhost:8090 (topics expedition.events and reservation.events)"
