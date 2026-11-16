#!/usr/bin/env sh
# Walks one party through the whole saga, through the gateway with Keycloak tokens: Wigglytuff publishes an
# expedition, four adventurers line up and hold roles, one misses the ready check (compensation), the party locks,
# runs through the flaky simulator, and gets certificates and notifications. Needs curl and jq.
#   READY_CHECK=PT15S docker compose -f expeditions/docker-compose.yml up --build -d
#   sh expeditions/scripts/demo.sh
# Then find the trace in Grafana (http://localhost:3000, Explore > Tempo): search by service "processing".
set -eu

API=${API:-http://localhost:8080}
KEYCLOAK=${KEYCLOAK:-http://localhost:8180}
SIM=${SIM:-http://localhost:8085}
RUN=$(date +%s)

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

token() { # username -> access token (password grant, demo users all use lantern-123)
  curl -sf "$KEYCLOAK/realms/guild/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=expeditions -d username="$1" -d password=lantern-123 | jq -r .access_token
}

say "Signing in through Keycloak"
for who in wigglytuff mira teo sol nina; do
  eval "TOKEN_$who=\$(token $who)"
  echo "  $who"
done
as() { who=$1; shift; eval "t=\$TOKEN_$who"; curl -sf -H "Authorization: Bearer $t" "$@"; }

OPENS=$(date -u -d '+5 seconds' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+5S +%Y-%m-%dT%H:%M:%SZ)
say "Wigglytuff (guild staff) publishes an expedition opening at $OPENS"
EXP=$(as wigglytuff -X POST "$API/api/expeditions" -H 'Content-Type: application/json' -d "{
  \"code\":\"demo-$RUN\",\"name\":\"Dark Crater, demo $RUN\",\"dungeon\":\"dark-crater\",\"difficulty\":\"STAR\",
  \"opensAt\":\"$OPENS\",\"parties\":2,\"composition\":{\"TANK\":1,\"HEALER\":1,\"DAMAGE\":2}}" | jq -r .id)
echo "  expedition $EXP"
printf '  an adventurer trying the same: HTTP %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/expeditions" \
  -H "Authorization: Bearer $TOKEN_mira" -H 'Content-Type: application/json' -d '{}')"

say "Four adventurers line up"
sleep 2 # let the queue hear about the expedition
for who in mira teo sol nina; do
  as $who -X POST "$API/api/expeditions/$EXP/queue" | jq -c "{who: \"$who\", state, position}"
done

admitted() {
  while :; do
    s=$(as "$1" "$API/api/expeditions/$EXP/queue")
    [ "$(echo "$s" | jq -r .state)" = ADMITTED ] && { echo "$s" | jq -r .admissionToken; return; }
    sleep 2
  done
}

hold() { # who role -> reservation id
  admission=$(admitted "$1")
  as "$1" -X POST "$API/api/expeditions/$EXP/reservations" -H 'Content-Type: application/json' \
    -d "{\"role\":\"$2\",\"admissionToken\":\"$admission\"}" | jq -r .id
}

say "Waiting for admission, then holding a role each"
TANK=$(hold mira TANK); HEALER=$(hold teo HEALER); DPS1=$(hold sol DAMAGE); DPS2=$(hold nina DAMAGE)
as mira "$API/api/reservations/$TANK" | jq -c '{party: .party.number, status: .party.status, readyBy: .party.readyBy, members: [.party.members[] | "\(.adventurerId):\(.role):\(.status)"]}'

say "Ready check: three confirm, nina doesn't answer"
for pair in "mira $TANK" "teo $HEALER" "sol $DPS1"; do
  set -- $pair; as "$1" -X POST "$API/api/reservations/$2/confirm" | jq -c "{who: \"$1\", status}"
done

say "Waiting for the ready check to run out..."
while [ "$(as mira "$API/api/reservations/$TANK" | jq -r .party.status)" = ASSEMBLED ]; do sleep 2; done
as nina "$API/api/reservations/$DPS2" | jq -c '{who: "nina", status}'
as mira "$API/api/reservations/$TANK" | jq -c '{who: "mira", status, heldUntil, party: .party.status}'

say "nina lines up again, gets the reopened slot, and everyone confirms"
as nina -X POST "$API/api/expeditions/$EXP/queue" | jq -c '{who: "nina", state, position}'
DPS2=$(hold nina DAMAGE)
for pair in "mira $TANK" "teo $HEALER" "sol $DPS1" "nina $DPS2"; do
  set -- $pair; as "$1" -X POST "$API/api/reservations/$2/confirm" | jq -c "{who: \"$1\", status, party: .party.status}"
done

PARTY=$(as mira "$API/api/reservations/$TANK" | jq -r .party.id)
say "The party is in the dungeon (the simulator is slow and fails on purpose; watch the attempts)"
while :; do
  run=$(as mira "$API/api/expeditions/$EXP/parties/$PARTY/runs" | jq -c '.[-1] // empty')
  [ -n "$run" ] && echo "$run" | jq -c '{status, failure, attempts, lastError}'
  [ -n "$run" ] && [ "$(echo "$run" | jq -r .status)" != PENDING ] && break
  sleep 2
done
as mira "$API/api/circuit" | jq -c .
RUN_ID=$(echo "$run" | jq -r .id)

sleep 2
say "Outcome"
as mira "$API/api/reservations/$TANK" | jq -c '{who: "mira", status, party: .party.status}'
as mira "$API/api/certificates" | jq -c ".[] | select(.runId == \"$RUN_ID\") | {certificate: .id, role, floorsCleared, pokeShare}"
say "mira's notifications, newest first"
as mira "$API/api/notifications" | jq -r ".[] | select(.expeditionId == $EXP) | \"  [\\(.kind)] \\(.message)\""

say "Capacity"
as mira "$API/api/expeditions/$EXP/capacity" | jq -c .
echo
echo "Run $RUN_ID: in Grafana (http://localhost:3000) open Explore > Tempo and search service.name=processing;"
echo "the trace goes gateway > reservation > processing > dungeon-sim > reservation + issuance."
echo "Make the dungeon worse with PUT $SIM/api/chaos (see expeditions/README.md)."
