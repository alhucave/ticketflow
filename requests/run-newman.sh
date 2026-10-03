#!/usr/bin/env bash
# Runs the Postman collection with Newman (Docker) against the local docker-compose stack.
#
# Newman runs INSIDE the compose network and reaches the app as http://app:8080 (API) and http://app:8081
# (management): the published ports are bound to 127.0.0.1 of the Docker host, which a container cannot reach
# (and on Colima/Docker Desktop "localhost" inside a container is the container itself). This works on Linux,
# Docker Desktop and Colima alike.
#
# Usage:  ADMIN_API_KEY=<same key the stack was started with> ./requests/run-newman.sh [extra newman args]
# The key is only passed through the environment; nothing secret lives in this file.
set -euo pipefail
cd "$(dirname "$0")/.."

NEWMAN_IMAGE="${NEWMAN_IMAGE:-postman/newman:6.1.3-alpine}"
# Write budget of the rate limiter: 20 burst, 1 token/s per client. A pause between requests keeps a full run
# (about 15 write requests) clear of 429 even right after another run; override with NEWMAN_DELAY_MS.
DELAY_MS="${NEWMAN_DELAY_MS:-400}"

if command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  COMPOSE=(docker compose)
fi

app_container="$("${COMPOSE[@]}" ps -q app)"
if [ -z "$app_container" ]; then
  echo "The compose stack is not running: start it first with 'docker-compose up --build -d'." >&2
  exit 1
fi
# Wait for the container healthcheck (liveness); the collection's first request then checks readiness.
for _ in $(seq 1 60); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$app_container")" = "healthy" ] && break
  sleep 2
done
network="$(docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}} {{end}}' "$app_container" | awk '{print $1}')"

exec docker run --rm --network "$network" \
  -v "$PWD/requests:/etc/newman:ro" \
  "$NEWMAN_IMAGE" run /etc/newman/ticketflow.postman_collection.json \
  -e /etc/newman/ticketflow.local.postman_environment.json \
  --env-var "baseUrl=http://app:8080" \
  --env-var "managementUrl=http://app:8081" \
  --env-var "adminKey=${ADMIN_API_KEY:-}" \
  --env-var "skipStream=true" \
  --delay-request "$DELAY_MS" \
  --color on \
  "$@"
