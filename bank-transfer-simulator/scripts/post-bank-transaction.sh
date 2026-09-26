#!/usr/bin/env bash
# Posts one bank transaction to bank-transfer-payment-service's `POST /bank-transactions`, the way the
# bank's file feed / webhook would. Authenticates as the bank-simulator service account (token.sh).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PAYMENT_URL="${PAYMENT_URL:-http://localhost:8081}"

usage() {
  cat <<'EOF'
Usage: post-bank-transaction.sh --reservation <id> --amount <amount> [options]

Posts a bank transaction to POST /bank-transactions (bank-transfer-payment-service).

Required (one of):
  --reservation <id>      Reservation id, e.g. P4145478. Used to build the remittance
                           information together with --e2e, unless --description is given.
  --description <text>    Raw remittanceInformation, verbatim. Overrides --reservation/--e2e.

Required:
  --amount <amount>       e.g. 120 or 120.50. Normalised to exactly 2 decimals; at most 2
                           decimal digits are accepted (120.123 is rejected).

Optional:
  --ref <ref>             bankTransactionRef. Default: BANK-TX-<UTC yyyymmddHHMMSS>-<4 random digits>.
  --e2e <10 chars>         End-to-end id used in the remittance information. Default: 10 random digits.
  --debtor <iban>          debtorAccountNumber. Default: NL91ABNA0417164300.
  --debtor-name <name>     debtorName. Default: A. Lovelace.
  --currency <code>        Default: EUR.
  --booked-at <timestamp>  ISO-8601 UTC, e.g. 2026-10-01T09:15:00Z. Default: now.
  -h, --help               Show this help and exit.

Environment:
  PAYMENT_URL              Default: http://localhost:8081.

Examples:
  post-bank-transaction.sh --reservation P4145478 --amount 120
  post-bank-transaction.sh --reservation P4145478 --amount 120 --ref BANK-TX-1
  post-bank-transaction.sh --description "garbage" --amount 50
  post-bank-transaction.sh --reservation P4145478 --amount 50 --currency USD
EOF
}

RESERVATION=""
AMOUNT=""
REF=""
E2E=""
DEBTOR="NL91ABNA0417164300"
DEBTOR_NAME="A. Lovelace"
CURRENCY="EUR"
DESCRIPTION=""
BOOKED_AT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --reservation) RESERVATION="$2"; shift 2 ;;
    --amount) AMOUNT="$2"; shift 2 ;;
    --ref) REF="$2"; shift 2 ;;
    --e2e) E2E="$2"; shift 2 ;;
    --debtor) DEBTOR="$2"; shift 2 ;;
    --debtor-name) DEBTOR_NAME="$2"; shift 2 ;;
    --currency) CURRENCY="$2"; shift 2 ;;
    --description) DESCRIPTION="$2"; shift 2 ;;
    --booked-at) BOOKED_AT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument '$1'" >&2; usage >&2; exit 1 ;;
  esac
done

for bin in curl jq; do
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "error: '$bin' is required but not found on PATH." >&2
    exit 1
  fi
done

if [[ -z "$AMOUNT" ]]; then
  echo "error: --amount is required." >&2
  exit 1
fi

if [[ -z "$RESERVATION" && -z "$DESCRIPTION" ]]; then
  echo "error: either --reservation or --description is required." >&2
  exit 1
fi

# Normalises an amount to exactly 2 decimal digits using string manipulation only (no floating
# point), so 120 -> 120.00, 120.5 -> 120.50, 120.25 -> 120.25. Rejects anything that is not
# digits with an optional 1-2 digit decimal part (e.g. "120.123", "abc", "-5", "1e3").
normalise_amount() {
  local raw="$1"
  if [[ ! "$raw" =~ ^[0-9]+(\.[0-9]{1,2})?$ ]]; then
    echo "error: --amount '$raw' is not a valid amount (expected digits, optionally with 1-2 decimal places, e.g. 120 or 120.50)" >&2
    exit 1
  fi
  if [[ "$raw" == *.* ]]; then
    local int_part="${raw%%.*}"
    local dec_part="${raw#*.}"
    if [[ ${#dec_part} -eq 1 ]]; then
      dec_part="${dec_part}0"
    fi
    printf '%s.%s' "$int_part" "$dec_part"
  else
    printf '%s.00' "$raw"
  fi
}

# Generates n random decimal digits (0-9 each), used for default ref suffixes and e2e ids.
random_digits() {
  local n="$1"
  local out=""
  while [[ ${#out} -lt $n ]]; do
    out="${out}$((RANDOM % 10))"
  done
  printf '%s' "${out:0:$n}"
}

NORMALISED_AMOUNT="$(normalise_amount "$AMOUNT")"

if [[ -z "$REF" ]]; then
  REF="BANK-TX-$(date -u +%Y%m%d%H%M%S)-$(random_digits 4)"
fi

if [[ -z "$BOOKED_AT" ]]; then
  BOOKED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
fi

if [[ -z "$DESCRIPTION" ]]; then
  if [[ -z "$E2E" ]]; then
    E2E="$(random_digits 10)"
  fi
  DESCRIPTION="${E2E} ${RESERVATION}"
fi

TOKEN="$("$SCRIPT_DIR/token.sh")"

BODY="$(jq -n \
  --arg ref "$REF" \
  --arg debtor "$DEBTOR" \
  --arg debtorName "$DEBTOR_NAME" \
  --argjson amount "$NORMALISED_AMOUNT" \
  --arg currency "$CURRENCY" \
  --arg remittance "$DESCRIPTION" \
  --arg bookedAt "$BOOKED_AT" \
  '{
    bankTransactionRef: $ref,
    debtorAccountNumber: $debtor,
    debtorName: $debtorName,
    amount: $amount,
    currency: $currency,
    remittanceInformation: $remittance,
    bookedAt: $bookedAt
  }')"

HTTP_RESPONSE="$(curl -s -w '\n%{http_code}' -X POST "$PAYMENT_URL/bank-transactions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "$BODY")"

HTTP_STATUS="${HTTP_RESPONSE##*$'\n'}"
RESPONSE_BODY="${HTTP_RESPONSE%$'\n'*}"

echo "HTTP $HTTP_STATUS"
if echo "$RESPONSE_BODY" | jq . >/dev/null 2>&1; then
  echo "$RESPONSE_BODY" | jq .
else
  echo "$RESPONSE_BODY"
fi

if [[ "$HTTP_STATUS" -ge 400 ]]; then
  exit 1
fi
