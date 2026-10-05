#!/usr/bin/env bash
# Smoke test of a ticketflow container image WITHOUT any infrastructure (no DynamoDB, no SQS): the container must
# start and its liveness probe (management port, /actuator/health/liveness) must answer 200 within a deadline.
# Readiness is expected to be DOWN here (no DynamoDB/SQS) and is only reported, never asserted (DP-037, DP-040).
#
# Usage: scripts/smoke-image.sh <image-ref> [expected-version] [deadline-seconds]
#   <image-ref>         anything `docker run` accepts; the release workflow passes ghcr.io/<owner>/ticketflow@sha256:...
#   [expected-version]  if given, the JSON logs must report service.version equal to it (e.g. 0.1.0)
#   [deadline-seconds]  how long to wait for liveness 200 (default 90)
# The same script runs in .github/workflows/release.yml (job `smoke`) and locally (e.g. on `ticketflow:local`).
# The container is always removed on exit. On failure the container logs are printed.
set -euo pipefail

image="${1:?usage: scripts/smoke-image.sh <image-ref> [expected-version] [deadline-seconds]}"
expected_version="${2:-}"
deadline="${3:-90}"
name="ticketflow-smoke-$$"

cleanup() {
  docker rm -f "$name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "SMOKE FAILED: $1" >&2
  echo "--- container state" >&2
  docker inspect --format 'status={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}}' "$name" >&2 || true
  echo "--- container logs" >&2
  docker logs "$name" >&2 || true
  exit 1
}

echo "==> Starting $image without infrastructure (hardened like docker-compose.yml)"
# Same runtime restrictions as the compose service (read-only root, tmpfs /tmp, no capabilities, memory limit).
# The management port is published on loopback only, on a random host port.
docker run -d --name "$name" \
  --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges:true --memory 512m \
  -p 127.0.0.1::8081 \
  "$image" >/dev/null

mgmt="$(docker port "$name" 8081/tcp 2>/dev/null | head -n 1 || true)"
[ -n "$mgmt" ] || fail "the container did not start (no published management port)"
url="http://${mgmt}/actuator/health"
echo "==> Waiting up to ${deadline}s for ${url}/liveness to answer 200"

start=$SECONDS
code=000
while :; do
  if [ "$(docker inspect --format '{{.State.Running}}' "$name")" != "true" ]; then
    fail "the container stopped before liveness answered 200"
  fi
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "${url}/liveness" || true)"
  [ "$code" = "200" ] && break
  if [ $((SECONDS - start)) -ge "$deadline" ]; then
    fail "liveness did not answer 200 within ${deadline}s (last status: ${code})"
  fi
  sleep 2
done
echo "OK: liveness 200 after $((SECONDS - start))s"
echo "info: readiness answers $(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "${url}/readiness" || true) (503 is expected without infrastructure)"

if [ -n "$expected_version" ]; then
  # ECS JSON logs: {"service":{"name":"ticketflow","version":"0.1.0",...}}
  logged="$(docker logs "$name" 2>&1 | grep -m1 -o '"service":{[^}]*"version":"[^"]*"' | sed 's/.*"version":"//; s/"$//' || true)"
  if [ "$logged" != "$expected_version" ]; then
    fail "expected service.version ${expected_version} in the logs, found '${logged:-nothing}'"
  fi
  echo "OK: logs report service.version=${logged}"
fi
echo "==> Smoke test passed"
