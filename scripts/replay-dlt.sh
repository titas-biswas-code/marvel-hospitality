#!/usr/bin/env bash
# Replays dead-lettered records from `<topic>.DLT` back onto `<topic>`: the manual operator tool ADR-0008 promises
# instead of automatic DLT re-consumption. Fix the cause first (a bug, a database outage), then replay.
#
# Safe to re-run, and safe to replay a record that was already processed: every consumer is idempotent through its
# inbox table (`processed_message`, keyed by paymentId / refundId / the `id` header, ADR-0006), so a duplicate is
# acknowledged and skipped. A record that still cannot be processed simply lands in the DLT again.
#
# For each replayed record:
#   - key and value are unchanged, so the record goes to the same partition as the original (a topic and its .DLT
#     have the same partition count, infra/kafka/create-topics.sh);
#   - the original headers (id, eventType, eventVersion, producer, occurredAt, propertyId, traceparent, ...) are kept,
#     a null-valued header stays null; spring-kafka's `kafka_dlt-*` headers are dropped (they describe the failed
#     delivery) and `replayedFrom=<topic>.DLT` is set (once, also when a record is replayed a second time).
#
# Progress is kept in consumer group `dlt-replay-<topic>`, committed only after the records were produced, so each
# dead letter is replayed once and nothing is skipped if a run fails half-way. --dry-run reads from the same position
# and commits nothing.
#
# How it works: Kafka's own console consumer and producer run inside the compose `kafka` container; python3 on the
# host does the parsing, on raw bytes. Plain line-based parsing does not work for real dead letters:
#   - `kafka_dlt-exception-stacktrace` / `-message` contain newlines;
#   - `kafka_dlt-original-offset` / `-partition` / `-timestamp` are binary numbers, so any single byte, control
#     characters included, can appear in them.
# The consumer output therefore uses multi-character separators (below) that neither a stack trace nor a 4- or
# 8-byte binary value can contain. The producer reads one record per line, so a record whose KEPT key, value or
# headers contain a line break (or a separator) cannot be replayed with these tools: the script then refuses the
# whole batch before producing anything and names the record.
#
# Requires: docker (compose), python3.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$REPO_ROOT/infra/.env"

KAFKA_BIN=/opt/kafka/bin
BOOTSTRAP_SERVERS="${BOOTSTRAP_SERVERS:-kafka:9092}"

if [[ -z "${COMPOSE:-}" ]]; then
  if [[ -f "$ENV_FILE" ]]; then
    COMPOSE="docker compose -f $REPO_ROOT/infra/docker-compose.yml --env-file $ENV_FILE"
  else
    COMPOSE="docker compose -f $REPO_ROOT/infra/docker-compose.yml"
  fi
fi

usage() {
  cat <<'EOF'
Usage: replay-dlt.sh <topic> [--max N] [--dry-run]

Replays dead-lettered records from "<topic>.DLT" back onto "<topic>".

Arguments:
  <topic>       Source topic, e.g. bank-transfer-payment-update. Reads "<topic>.DLT".
                Must not itself end in ".DLT".

Options:
  --max N       Replay (or preview) at most N records.
  --dry-run     Show what would be replayed. Nothing is produced and no offsets are committed.
  -h, --help    Show this help and exit.

Environment:
  COMPOSE             Command run as "$COMPOSE exec -T kafka <...>".
                      Default: docker compose -f infra/docker-compose.yml [--env-file infra/.env]
  BOOTSTRAP_SERVERS   Default: kafka:9092 (as infra/kafka/create-topics.sh).

Examples:
  replay-dlt.sh bank-transfer-payment-update --dry-run
  replay-dlt.sh bank-transfer-payment-update
  replay-dlt.sh refund-requested --max 5
EOF
}

TOPIC=""
MAX_MESSAGES=""
DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --max) MAX_MESSAGES="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "error: unknown option '$1'" >&2; usage >&2; exit 1 ;;
    *)
      if [[ -n "$TOPIC" ]]; then
        echo "error: unexpected extra argument '$1'" >&2; usage >&2; exit 1
      fi
      TOPIC="$1"; shift ;;
  esac
done

if [[ -z "$TOPIC" ]]; then
  echo "error: <topic> is required." >&2; usage >&2; exit 1
fi
if [[ "$TOPIC" == *.DLT ]]; then
  echo "error: '$TOPIC' is a dead-letter topic; pass the SOURCE topic (e.g. 'bank-transfer-payment-update')." >&2
  exit 1
fi
if [[ -n "$MAX_MESSAGES" && ! "$MAX_MESSAGES" =~ ^[1-9][0-9]*$ ]]; then
  echo "error: --max must be a positive integer, got '$MAX_MESSAGES'." >&2; exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "error: python3 is required (it parses the dead letters on raw bytes)." >&2; exit 1
fi

DLT_TOPIC="${TOPIC}.DLT"
GROUP="dlt-replay-${TOPIC}"

compose_exec() {
  # shellcheck disable=SC2086 # COMPOSE is a command line, word-split on purpose (like the Makefile's $(COMPOSE))
  $COMPOSE exec -T kafka "$@"
}

if ! $COMPOSE ps --status running --services 2>/dev/null | grep -qx kafka; then
  echo "error: the compose 'kafka' service is not running. Start it first: make up" >&2
  exit 1
fi

# Separators: ASCII only (safe to pass as arguments), long enough that no 4/8-byte binary header value can contain
# one, and without self-overlap so the first match is always the real one.
COL_SEP='<~dlt:col~>'
HDR_SEP='<~dlt:hdr~>'
REC_SEP='<~dlt:rec~>'

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

consumer_args=(
  "$KAFKA_BIN/kafka-console-consumer.sh"
  --bootstrap-server "$BOOTSTRAP_SERVERS"
  --topic "$DLT_TOPIC"
  --group "$GROUP"
  # Never commit while reading: offsets move only after a successful produce (see the end of this script).
  --command-property enable.auto.commit=false
  --command-property auto.offset.reset=earliest
  --timeout-ms 10000
  --formatter-property print.partition=true
  --formatter-property print.offset=true
  --formatter-property print.headers=true
  --formatter-property print.key=true
  --formatter-property print.value=true
  --formatter-property "key.separator=$COL_SEP"
  --formatter-property "headers.separator=$HDR_SEP"
  --formatter-property "line.separator=$REC_SEP"
)
if [[ -n "$MAX_MESSAGES" ]]; then
  consumer_args+=(--max-messages "$MAX_MESSAGES")
fi

# The console consumer exits non-zero when --timeout-ms expires on a drained topic; that is the normal end here.
compose_exec "${consumer_args[@]}" >"$WORK_DIR/raw" 2>"$WORK_DIR/consumer.err" || true
if grep -qE "ERROR|Exception" "$WORK_DIR/consumer.err" && ! grep -q "TimeoutException" "$WORK_DIR/consumer.err"; then
  echo "error: kafka-console-consumer.sh failed:" >&2
  cat "$WORK_DIR/consumer.err" >&2
  exit 1
fi

# Parse and validate every record before producing anything. Writes:
#   producer-input  one line per record: headers<COL>key<COL>value (the console producer's parse.headers format)
#   offsets         "<partition> <next offset>" per DLT partition read, for the commit after producing
#   preview         human-readable lines for --dry-run
#   count           number of records
python3 - "$WORK_DIR" "$DLT_TOPIC" "$COL_SEP" "$HDR_SEP" "$REC_SEP" <<'PY'
import sys

work, dlt_topic, col, hdr, rec = sys.argv[1], sys.argv[2], *(s.encode() for s in sys.argv[3:6])
raw = open(f"{work}/raw", "rb").read()

def clean(field: bytes, what: str, where: str) -> str:
    for bad, name in ((b"\n", "a line break"), (b"\r", "a line break"), (col, "a separator"), (hdr, "a separator")):
        if bad in field:
            sys.exit(f"error: refusing to replay anything from {dlt_topic}: {where}: {what} contains {name}, which "
                     "the console producer cannot reproduce. Nothing was produced; inspect that record by hand.")
    try:
        return field.decode("utf-8")
    except UnicodeDecodeError:
        sys.exit(f"error: refusing to replay anything from {dlt_topic}: {where}: {what} is not UTF-8 text. "
                 "Nothing was produced; inspect that record by hand.")

lines, preview, next_offsets = [], [], {}
for record in raw.split(rec):
    if not record.strip():
        continue
    parts = record.split(col, 4)
    if len(parts) != 5 or not parts[0].startswith(b"Partition:") or not parts[1].startswith(b"Offset:"):
        sys.exit(f"error: unexpected console consumer output, nothing was produced: {record[:200]!r}")
    partition, offset = int(parts[0][len(b"Partition:"):]), int(parts[1][len(b"Offset:"):])
    where = f"record at partition {partition}, offset {offset}"
    headers_block, key, value = parts[2], parts[3], parts[4]

    kept = []
    if headers_block != b"NO_HEADERS":
        for entry in headers_block.split(hdr):
            name, _, header_value = entry.partition(b":")
            if name.startswith(b"kafka_dlt-") or name == b"replayedFrom":
                continue  # the failed delivery's diagnostics, and our own marker from an earlier replay
            kept.append(f"{clean(name, 'header name', where)}:{clean(header_value, 'header ' + name.decode('utf-8', 'replace'), where)}")
    kept.append(f"replayedFrom:{dlt_topic}")

    key_text, value_text = clean(key, "the key", where), clean(value, "the value", where)
    lines.append(col.decode().join((hdr.decode().join(kept), key_text, value_text)))
    preview.append(f"  {partition}@{offset}  key={key_text}  headers=[{', '.join(kept)}]  value={value_text}")
    next_offsets[partition] = max(next_offsets.get(partition, 0), offset + 1)

with open(f"{work}/producer-input", "w", encoding="utf-8") as out:
    out.writelines(line + "\n" for line in lines)
with open(f"{work}/offsets", "w") as out:
    out.writelines(f"{p} {o}\n" for p, o in sorted(next_offsets.items()))
with open(f"{work}/preview", "w", encoding="utf-8") as out:
    out.writelines(line + "\n" for line in preview)
with open(f"{work}/count", "w") as out:
    out.write(str(len(lines)))
PY

COUNT="$(cat "$WORK_DIR/count")"

if [[ "$DRY_RUN" == true ]]; then
  cat "$WORK_DIR/preview"
  echo "Would replay $COUNT record(s) from $DLT_TOPIC to $TOPIC (dry run: nothing produced, no offsets committed)"
  exit 0
fi

if [[ "$COUNT" -eq 0 ]]; then
  echo "Replayed 0 record(s) from $DLT_TOPIC to $TOPIC"
  exit 0
fi

# A null header value or key is printed as the text "null" by the consumer; null.marker turns it back into null.
# (A header whose real value is the string "null" would become null too; none of the contract headers is.)
compose_exec "$KAFKA_BIN/kafka-console-producer.sh" \
  --bootstrap-server "$BOOTSTRAP_SERVERS" \
  --topic "$TOPIC" \
  --command-property acks=all \
  --reader-property parse.key=true \
  --reader-property parse.headers=true \
  --reader-property "key.separator=$COL_SEP" \
  --reader-property "headers.delimiter=$COL_SEP" \
  --reader-property "headers.separator=$HDR_SEP" \
  --reader-property null.marker=null \
  <"$WORK_DIR/producer-input"

# Only now remember how far we got. The console consumer has exited, so the group is empty and may be moved.
while read -r partition next_offset; do
  compose_exec "$KAFKA_BIN/kafka-consumer-groups.sh" --bootstrap-server "$BOOTSTRAP_SERVERS" --group "$GROUP" \
    --reset-offsets --topic "$DLT_TOPIC:$partition" --to-offset "$next_offset" --execute >/dev/null
done <"$WORK_DIR/offsets"

echo "Replayed $COUNT record(s) from $DLT_TOPIC to $TOPIC"
