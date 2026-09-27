#!/usr/bin/env bash
# Demo convenience only: a real bank never knows a reservation's total or already-received amount;
# it only ever sees what the debtor instructed it to transfer. This script exists to make manually
# demoing the "pay exactly what's owed" happy path easy: it reads the reservation as a hotel-side
# user to compute the outstanding amount, then posts a bank transaction for exactly that amount (or, with --extra,
# that much more, to demo an overpayment and its refund).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RESERVATION_URL="${RESERVATION_URL:-http://localhost:8080}"

usage() {
  cat <<'EOF'
Usage: pay-in-full.sh --property <propertyId> --reservation <id> [--extra <amount>] [--user <username>] [flags...]

Reads GET /properties/{propertyId}/reservations/{reservationId} (as a dev user, default alice),
computes outstanding = totalAmount - amountReceived using integer-cents arithmetic (no floating
point), refuses if the reservation is not PENDING_PAYMENT or outstanding <= 0, then posts a bank
transaction for exactly that amount via post-bank-transaction.sh. With --extra the transaction is that much larger:
the reservation is confirmed as OVERPAID and the surplus is refunded (refund saga, ADR-0006).

Options:
  --property <id>       Property id, e.g. AMS01. Required.
  --reservation <id>     Reservation id, e.g. P4145478. Required.
  --extra <amount>       Pay this much more than outstanding, e.g. 10 or 10.50 (at most 2 decimals, > 0).
                          Default: nothing extra.
  --user <username>      Dev user for the read; must have reservation:read on the property.
                          Default: alice.
  -h, --help             Show this help and exit.

Any other flag is passed through to post-bank-transaction.sh (e.g. --ref, --currency, --debtor).
--amount and --reservation are always set by this script; do not pass them again.

Environment:
  RESERVATION_URL        Default: http://localhost:8080.

Example:
  pay-in-full.sh --property AMS01 --reservation P4145478
  pay-in-full.sh --property AMS01 --reservation P4145478 --ref BANK-TX-1
  pay-in-full.sh --property AMS01 --reservation P4145478 --extra 10
EOF
}

PROPERTY=""
RESERVATION=""
USER_NAME="alice"
EXTRA=""
PASSTHROUGH=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --property) PROPERTY="$2"; shift 2 ;;
    --reservation) RESERVATION="$2"; shift 2 ;;
    --user) USER_NAME="$2"; shift 2 ;;
    --extra) EXTRA="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) PASSTHROUGH+=("$1"); shift ;;
  esac
done

for bin in curl jq; do
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "error: '$bin' is required but not found on PATH." >&2
    exit 1
  fi
done

if [[ -z "$PROPERTY" || -z "$RESERVATION" ]]; then
  echo "error: --property and --reservation are required." >&2
  usage >&2
  exit 1
fi

# Converts a 0-2 decimal amount string (as returned by the reservation service) to integer cents,
# without floating point, so the outstanding-balance subtraction below is exact.
to_cents() {
  local raw="$1"
  if [[ ! "$raw" =~ ^[0-9]+(\.[0-9]{1,2})?$ ]]; then
    echo "error: unexpected amount format '$raw' from the reservation service." >&2
    exit 1
  fi
  local int_part dec_part
  if [[ "$raw" == *.* ]]; then
    int_part="${raw%%.*}"
    dec_part="${raw#*.}"
    if [[ ${#dec_part} -eq 1 ]]; then
      dec_part="${dec_part}0"
    fi
  else
    int_part="$raw"
    dec_part="00"
  fi
  printf '%d' "$((10#${int_part} * 100 + 10#${dec_part}))"
}

EXTRA_CENTS=0
if [[ -n "$EXTRA" ]]; then
  if [[ ! "$EXTRA" =~ ^[0-9]+(\.[0-9]{1,2})?$ ]]; then
    echo "error: --extra must be a positive amount with at most 2 decimals, got '$EXTRA'." >&2
    exit 1
  fi
  EXTRA_CENTS="$(to_cents "$EXTRA")"
  if [[ "$EXTRA_CENTS" -le 0 ]]; then
    echo "error: --extra must be greater than 0, got '$EXTRA'." >&2
    exit 1
  fi
fi

TOKEN="$(bash "$SCRIPT_DIR/user-token.sh" "$USER_NAME")"

HTTP_RESPONSE="$(curl -s -w '\n%{http_code}' -X GET \
  "$RESERVATION_URL/properties/$PROPERTY/reservations/$RESERVATION" \
  -H "Authorization: Bearer $TOKEN")"

HTTP_STATUS="${HTTP_RESPONSE##*$'\n'}"
RESPONSE_BODY="${HTTP_RESPONSE%$'\n'*}"

if [[ "$HTTP_STATUS" -ge 400 ]]; then
  echo "error: GET reservation failed with HTTP $HTTP_STATUS: $RESPONSE_BODY" >&2
  exit 1
fi

STATUS="$(echo "$RESPONSE_BODY" | jq -r '.status')"
TOTAL_AMOUNT="$(echo "$RESPONSE_BODY" | jq -r '.totalAmount')"
AMOUNT_RECEIVED="$(echo "$RESPONSE_BODY" | jq -r '.amountReceived')"

if [[ "$STATUS" != "PENDING_PAYMENT" ]]; then
  echo "error: reservation $RESERVATION is $STATUS, not PENDING_PAYMENT; nothing to pay." >&2
  exit 1
fi

TOTAL_CENTS="$(to_cents "$TOTAL_AMOUNT")"
RECEIVED_CENTS="$(to_cents "$AMOUNT_RECEIVED")"
OUTSTANDING_CENTS="$((TOTAL_CENTS - RECEIVED_CENTS))"

if [[ "$OUTSTANDING_CENTS" -le 0 ]]; then
  echo "error: reservation $RESERVATION has no outstanding balance (total $TOTAL_AMOUNT, received $AMOUNT_RECEIVED)." >&2
  exit 1
fi

format_cents() {
  printf '%d.%02d' "$(($1 / 100))" "$(($1 % 100))"
}

OUTSTANDING_AMOUNT="$(format_cents "$OUTSTANDING_CENTS")"
PAY_AMOUNT="$(format_cents "$((OUTSTANDING_CENTS + EXTRA_CENTS))")"

if [[ "$EXTRA_CENTS" -gt 0 ]]; then
  echo "Reservation $RESERVATION: total $TOTAL_AMOUNT, received $AMOUNT_RECEIVED, paying outstanding $OUTSTANDING_AMOUNT + extra $(format_cents "$EXTRA_CENTS") = $PAY_AMOUNT (the extra is refunded)" >&2
else
  echo "Reservation $RESERVATION: total $TOTAL_AMOUNT, received $AMOUNT_RECEIVED, paying outstanding $OUTSTANDING_AMOUNT" >&2
fi

if [[ ${#PASSTHROUGH[@]} -gt 0 ]]; then
  bash "$SCRIPT_DIR/post-bank-transaction.sh" --reservation "$RESERVATION" --amount "$PAY_AMOUNT" "${PASSTHROUGH[@]}"
else
  bash "$SCRIPT_DIR/post-bank-transaction.sh" --reservation "$RESERVATION" --amount "$PAY_AMOUNT"
fi
