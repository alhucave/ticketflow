#!/usr/bin/env bash
# End-to-end demo of the main ticketflow flow with plain curl: create an event, buy tickets (202), poll the order
# until SOLD, replay the same Idempotency-Key, show a 409 and a 400, issue complimentary tickets (admin) and watch
# the availability stream.
#
# Usage:  ADMIN_API_KEY=<key the stack was started with> ./requests/demo.sh
# Optional: BASE_URL (default http://localhost:8080), MANAGEMENT_URL (default http://localhost:8081).
# Without ADMIN_API_KEY the complimentary step is skipped. Nothing secret lives in this file.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
MANAGEMENT_URL="${MANAGEMENT_URL:-http://localhost:8081}"
ADMIN_API_KEY="${ADMIN_API_KEY:-}"

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
pretty() { if command -v jq >/dev/null 2>&1; then jq . 2>/dev/null || cat; else cat; fi; }
json_field() { sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p" | head -n1; }
new_key() { printf 'demo-%s' "$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')"; }   # 29 chars, within 16-128

# curl wrapper: prints "<status>" on the first line, then the body.
call() {
  local out
  out="$(curl -sS -w '\n%{http_code}' "$@")"
  CODE="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
  echo "HTTP $CODE"
  printf '%s\n' "$BODY" | pretty
}

step "0. Waiting for readiness ($MANAGEMENT_URL/actuator/health/readiness)"
for _ in $(seq 1 60); do
  if curl -sf "$MANAGEMENT_URL/actuator/health/readiness" >/dev/null; then break; fi
  sleep 2
done
curl -sf "$MANAGEMENT_URL/actuator/health/readiness" | pretty

step "1. Create an event (capacity 100)"
STARTS_AT="$(date -u -v+1y +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+1 year' +%Y-%m-%dT%H:%M:%SZ)"
call -X POST "$BASE_URL/events" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Demo Rock Night\",\"startsAt\":\"$STARTS_AT\",\"venue\":\"Arena\",\"capacity\":100}"
EVENT_ID="$(printf '%s' "$BODY" | json_field id)"
echo "eventId = $EVENT_ID"

step "2. Buy 3 tickets: 202 immediately, the order is processed asynchronously"
KEY="$(new_key)"
call -X POST "$BASE_URL/orders" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "{\"eventId\":\"$EVENT_ID\",\"quantity\":3}"
ORDER_ID="$(printf '%s' "$BODY" | json_field orderId)"
echo "orderId = $ORDER_ID  (Idempotency-Key = $KEY)"

step "3. Poll the order until it is SOLD"
STATUS=""
for _ in $(seq 1 30); do
  BODY="$(curl -sS "$BASE_URL/orders/$ORDER_ID")"
  STATUS="$(printf '%s' "$BODY" | json_field status)"
  echo "status = $STATUS"
  [ "$STATUS" = "SOLD" ] && break
  sleep 1
done
printf '%s\n' "$BODY" | pretty
[ "$STATUS" = "SOLD" ] || { echo "The order did not reach SOLD" >&2; exit 1; }

step "4. Availability: 3 sold, 97 available"
call "$BASE_URL/events/$EVENT_ID/availability"

step "5. Replay the same Idempotency-Key and body: 202 with the SAME order, nothing reserved twice"
call -X POST "$BASE_URL/orders" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "{\"eventId\":\"$EVENT_ID\",\"quantity\":3}"
[ "$(printf '%s' "$BODY" | json_field orderId)" = "$ORDER_ID" ] || { echo "Replay returned another order" >&2; exit 1; }

step "6. Same key, different body: 409 idempotency-key-reused"
call -X POST "$BASE_URL/orders" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "{\"eventId\":\"$EVENT_ID\",\"quantity\":4}"
[ "$CODE" = "409" ] || { echo "Expected 409" >&2; exit 1; }

step "7. No Idempotency-Key: 400 invalid-idempotency-key"
call -X POST "$BASE_URL/orders" -H 'Content-Type: application/json' -d "{\"eventId\":\"$EVENT_ID\",\"quantity\":1}"
[ "$CODE" = "400" ] || { echo "Expected 400" >&2; exit 1; }

if [ -n "$ADMIN_API_KEY" ]; then
  step "8. Issue 2 complimentary tickets (admin): COMPLIMENTARY, never counted as sold"
  call -X POST "$BASE_URL/events/$EVENT_ID/complimentary" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(new_key)" -H "X-Admin-Key: $ADMIN_API_KEY" -d '{"quantity":2,"reason":"demo VIP guests"}'
  [ "$CODE" = "201" ] || { echo "Expected 201" >&2; exit 1; }
  call "$BASE_URL/events/$EVENT_ID/availability"
else
  step "8. Complimentary tickets skipped: set ADMIN_API_KEY (the key the stack was started with) to run this step"
fi

step "9. Availability stream (SSE): current value, then every change (closing after 3 s)"
curl -sN --max-time 3 -H 'Accept: text/event-stream' "$BASE_URL/events/$EVENT_ID/availability/stream" || true
echo

step "10. Business metrics (management port)"
curl -sf "$MANAGEMENT_URL/actuator/prometheus" | grep -E '^ticketflow_(orders_placed|orders_sold|purchases_replayed|complimentary_issued)_total' || true

printf '\n\033[1mDemo finished OK\033[0m\n'
