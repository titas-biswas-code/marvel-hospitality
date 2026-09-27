#!/usr/bin/env bash
# End-to-end smoke test: the README's 5-minute demo, with assertions, against the running compose stack
# (`make up-apps`). Every step prints one line; the first failed assertion stops the run with a non-zero exit code
# and says what was expected and what came back.
#
#   infra/e2e/smoke.sh
#
# Covers the whole saga (ADR-0006): cash booking, bank-transfer booking paid in two parts until CONFIRMED and the
# customer notified, an overpayment refunded, and a reservation cancelled at its payment deadline whose late
# payment is refunded as well. Each run books random dates, so it can be repeated on the same stack.
#
# Needs bash, curl, jq and docker compose (for the deadline step). Environment overrides:
#   RESERVATION_URL (http://localhost:8080)  PAYMENT_URL (http://localhost:8081)  NOTIFICATION_URL (http://localhost:8082)
#   CONNECT_URL (http://localhost:8083)      PROPERTY (AMS01)                    DEMO_USER (alice)
#   EVENT_TIMEOUT (30, seconds to wait for an event-driven change)
#   AUTO_CANCEL_TIMEOUT (150, seconds to wait for the auto-cancel job; it runs every 60 s by default)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SIMULATOR="$REPO_ROOT/bank-transfer-simulator/scripts"

RESERVATION_URL="${RESERVATION_URL:-http://localhost:8080}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8081}"
NOTIFICATION_URL="${NOTIFICATION_URL:-http://localhost:8082}"
CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
PROPERTY="${PROPERTY:-AMS01}"
DEMO_USER="${DEMO_USER:-alice}"
EVENT_TIMEOUT="${EVENT_TIMEOUT:-30}"
AUTO_CANCEL_TIMEOUT="${AUTO_CANCEL_TIMEOUT:-150}"
COMPOSE=(docker compose -f "$REPO_ROOT/infra/docker-compose.yml" --env-file "$REPO_ROOT/infra/.env")

export RESERVATION_URL PAYMENT_URL   # read by the simulator scripts

for bin in curl jq docker; do
  command -v "$bin" >/dev/null 2>&1 || { echo "error: '$bin' is required but not found on PATH." >&2; exit 1; }
done
[[ -f "$REPO_ROOT/infra/.env" ]] || { echo "error: infra/.env not found; start the stack with 'make up-apps' first." >&2; exit 1; }

# ---------------------------------------------------------------------------------------------------- output

STEP=0
STARTED=$SECONDS
step() { STEP=$((STEP + 1)); printf '\n==> %d. %s\n' "$STEP" "$1"; }
ok()   { printf '    ok   %s\n' "$1"; }
fail() { printf '    FAIL %s\n' "$1" >&2; [[ -n "${2:-}" ]] && printf '%s\n' "$2" | sed 's/^/         /' >&2; exit 1; }

# ---------------------------------------------------------------------------------------------------- HTTP

# api METHOD URL [JSON body] -> sets STATUS and BODY; never exits on an HTTP error (the caller asserts).
api() {
  local method="$1" url="$2" data="${3:-}" response
  local args=(-s -w '\n%{http_code}' -X "$method" "$url" -H "Authorization: Bearer $TOKEN")
  [[ -n "$data" ]] && args+=(-H 'Content-Type: application/json' -d "$data")
  response="$(curl "${args[@]}")" || fail "$method $url: no response (is the stack up?)"
  STATUS="${response##*$'\n'}"
  BODY="${response%$'\n'*}"
}

expect_status() {
  [[ "$STATUS" == "$1" ]] || fail "expected HTTP $1, got $STATUS" "$BODY"
}

# expect JQ_FILTER DESCRIPTION -> the filter must yield true on BODY.
expect() {
  [[ "$(jq -r "$1" <<<"$BODY")" == "true" ]] || fail "$2 ($1)" "$BODY"
  ok "$2"
}

# wait_until TIMEOUT DESCRIPTION FUNCTION -> calls FUNCTION (which sets BODY and returns 0 when done) every second.
wait_until() {
  local timeout="$1" description="$2" probe="$3" deadline=$((SECONDS + $1))
  until "$probe"; do
    ((SECONDS < deadline)) || fail "$description: not reached within ${timeout}s" "$BODY"
    sleep 1
  done
  ok "$description ($((timeout - deadline + SECONDS))s)"
}

# ---------------------------------------------------------------------------------------------------- domain helpers

# A random 2-night stay somewhere in the 1000 years from 2030 (as the Postman demos do), so re-runs practically never
# collide; a collision is retried with new dates anyway. Dates are computed with jq so the script runs unchanged on
# GNU (CI) and BSD (macOS) systems.
random_stay() {
  local days=$(((RANDOM * 32768 + RANDOM) % 365000))
  jq -rn --argjson d "$days" '1893456000 + $d * 86400 | [strftime("%Y-%m-%d"), (. + 172800 | strftime("%Y-%m-%d"))] | join(" ")'
}

# book ROOM SEGMENT MODE -> 201 with RESERVATION set to the new id (BODY holds the reservation).
book() {
  local room="$1" segment="$2" mode="$3" attempt start end
  for attempt in 1 2 3 4 5; do
    read -r start end < <(random_stay)
    api POST "$RESERVATION_URL/properties/$PROPERTY/reservations" "$(jq -n \
      --arg room "$room" --arg segment "$segment" --arg mode "$mode" --arg start "$start" --arg end "$end" \
      '{customerName: "Ada Lovelace", roomNumber: $room, startDate: $start, endDate: $end,
        roomSegment: $segment, paymentMode: $mode}')"
    [[ "$STATUS" == 409 ]] || break
  done
  expect_status 201
  RESERVATION="$(jq -r .reservationId <<<"$BODY")"
  ok "reservation $RESERVATION: room $room, $start -> $end, $mode"
}

get_reservation() { api GET "$RESERVATION_URL/properties/$PROPERTY/reservations/$RESERVATION"; expect_status 200; }
get_payments()    { api GET "$RESERVATION_URL/properties/$PROPERTY/reservations/$RESERVATION/payments"; expect_status 200; }
get_notifications() { api GET "$NOTIFICATION_URL/notifications?reservationId=$RESERVATION"; expect_status 200; }

# Probes for wait_until; the expected value is passed through globals to keep them argument-free.
is_status()          { get_reservation;  [[ "$(jq -r .status <<<"$BODY")" == "$WANT" ]]; }
has_received()       { get_reservation;  [[ "$(jq -r ".amountReceived == $WANT" <<<"$BODY")" == true ]]; }
has_templates()      { get_notifications; [[ "$(jq -r --argjson want "$WANT" '[.[].template] | sort == ($want | sort)' <<<"$BODY")" == true ]]; }
refund_settled()     { get_payments;     [[ "$(jq -r 'last.refund.status // "" | IN("COMPLETED", "FAILED")' <<<"$BODY")" == true ]]; }

# pay SCRIPT ARGS... -> runs a simulator script, shows its output only when it fails.
pay() {
  local script="$1" out
  shift
  out="$(bash "$SIMULATOR/$script" "$@" 2>&1)" || fail "bank simulator: $script $*" "$out"
  ok "bank transaction posted: $(sed -n 's/.*"paymentId": "\([^"]*\)".*/paymentId \1/p' <<<"$out" | head -1)"
}

# ---------------------------------------------------------------------------------------------------- the demo

step "Stack is ready"
for url in "$RESERVATION_URL" "$PAYMENT_URL" "$NOTIFICATION_URL"; do
  code="$(curl -s -o /dev/null -w '%{http_code}' "$url/actuator/health/readiness" || true)"
  [[ "$code" == 200 ]] || fail "$url/actuator/health/readiness answered $code; run 'make up-apps'"
done
ok "reservation, payment and notification services are ready"
BODY="$(curl -sf "$CONNECT_URL/connectors?expand=status")" || fail "Kafka Connect does not answer at $CONNECT_URL"
expect '[.["reservation-outbox", "payment-outbox"] | .status.connector.state, .status.tasks[].state] | all(. == "RUNNING")' \
  "Debezium connectors reservation-outbox and payment-outbox are RUNNING"

step "Access token for $DEMO_USER (password grant, Keycloak)"
TOKEN="$(bash "$SIMULATOR/user-token.sh" "$DEMO_USER")" || fail "no token for $DEMO_USER from Keycloak"
ok "token issued; properties claim: $(jq -Rr 'split(".")[1] | gsub("-"; "+") | gsub("_"; "/") | @base64d | fromjson | .properties | join(", ")' <<<"$TOKEN" 2>/dev/null || echo '?')"

step "CASH reservation is confirmed at once"
book 101 SMALL CASH
expect '.status == "CONFIRMED" and .totalAmount == 160 and .paymentDeadlineAt == null' "CONFIRMED, total 160.00, no payment deadline"

step "BANK_TRANSFER reservation waits for the money"
book 202 MEDIUM BANK_TRANSFER
expect '.status == "PENDING_PAYMENT" and .totalAmount == 240 and .amountReceived == 0' "PENDING_PAYMENT, total 240.00, nothing received"
expect '.paymentDeadlineAt != null and (.bankTransferInstructions | contains("240.00 EUR"))' "payment deadline set, instructions say 240.00 EUR"

step "The bank reports a partial payment of 100.00"
pay post-bank-transaction.sh --reservation "$RESERVATION" --amount 100
WANT=100; wait_until "$EVENT_TIMEOUT" "amountReceived is 100.00" has_received
expect '.status == "PENDING_PAYMENT"' "still PENDING_PAYMENT"

step "The bank reports the remaining 140.00"
pay pay-in-full.sh --property "$PROPERTY" --reservation "$RESERVATION"
WANT=CONFIRMED; wait_until "$EVENT_TIMEOUT" "status is CONFIRMED" is_status
expect '.amountReceived == 240' "amountReceived is 240.00"
get_payments
expect '[.[].outcome] == ["MATCHED_PARTIAL", "MATCHED_FULL"]' "payments matched as MATCHED_PARTIAL, MATCHED_FULL"

step "The customer is notified of every status change"
WANT='["RESERVATION_CREATED_PENDING_PAYMENT","PARTIAL_PAYMENT_RECEIVED","RESERVATION_CONFIRMED"]'
wait_until "$EVENT_TIMEOUT" "notifications: booking, partial payment, confirmation" has_templates
jq -r 'last.renderedText' <<<"$BODY" | sed '/^$/d; s/^/         | /'

step "An overpayment confirms the reservation and refunds the surplus"
book 201 MEDIUM BANK_TRANSFER
pay pay-in-full.sh --property "$PROPERTY" --reservation "$RESERVATION" --extra 10
WANT=CONFIRMED; wait_until "$EVENT_TIMEOUT" "status is CONFIRMED" is_status
wait_until "$EVENT_TIMEOUT" "refund settled by the payment service" refund_settled
expect 'last | .outcome == "OVERPAID" and .refund.amount == 10 and .refund.reason == "OVERPAYMENT" and .refund.status == "COMPLETED"' \
  "OVERPAID; refund of 10.00 (OVERPAYMENT) COMPLETED"

step "An unpaid BANK_TRANSFER reservation is cancelled at its payment deadline"
book 301 LARGE BANK_TRANSFER
expect '.status == "PENDING_PAYMENT" and .totalAmount == 360' "PENDING_PAYMENT, total 360.00"
# The deadline is always a local midnight days away; moving the stored value is what the passing of time would do.
"${COMPOSE[@]}" exec -T postgres psql -q -U reservation -d reservation -v ON_ERROR_STOP=1 \
  -c "UPDATE reservation SET payment_deadline_at = now() WHERE reservation_id = '$RESERVATION'" >/dev/null \
  || fail "could not move the payment deadline (docker compose exec postgres)"
ok "payment deadline moved to now"
WANT=CANCELLED; wait_until "$AUTO_CANCEL_TIMEOUT" "status is CANCELLED by the auto-cancel job" is_status
WANT='["RESERVATION_CREATED_PENDING_PAYMENT","RESERVATION_CANCELLED_PAYMENT_DEADLINE_MISSED"]'
wait_until "$EVENT_TIMEOUT" "notifications: booking, cancellation (deadline missed)" has_templates

step "Money that arrives after the cancellation is refunded in full"
pay post-bank-transaction.sh --reservation "$RESERVATION" --amount 360
wait_until "$EVENT_TIMEOUT" "refund settled by the payment service" refund_settled
expect 'last | .outcome == "UNMATCHED_NOT_PENDING" and .refund.amount == 360 and .refund.reason == "RESERVATION_CANCELLED" and .refund.status == "COMPLETED"' \
  "UNMATCHED_NOT_PENDING; refund of 360.00 (RESERVATION_CANCELLED) COMPLETED"
get_reservation
expect '.status == "CANCELLED" and .amountReceived == 0' "reservation stays CANCELLED, nothing counted as received"

printf '\nSmoke test passed: %d steps in %ds.\n' "$STEP" "$((SECONDS - STARTED))"
