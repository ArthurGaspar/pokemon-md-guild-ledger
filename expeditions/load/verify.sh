#!/usr/bin/env sh
# Runs verify.sql against the compose stack's reservation database.
#   sh expeditions/load/verify.sh <expedition id>
set -eu
EXP=${1:?usage: verify.sh <expedition id>}
DIR=$(dirname "$0")
docker compose -f "$DIR/../docker-compose.yml" exec -T postgres \
  psql -U guild -d reservation -v exp="$EXP" -v ON_ERROR_STOP=1 < "$DIR/verify.sql"
