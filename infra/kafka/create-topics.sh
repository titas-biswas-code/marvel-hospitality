#!/usr/bin/env bash
# One-shot topic creation (contracts/events.md). Applications never auto-create topics.
# Idempotent: --if-not-exists, safe to re-run with `docker compose run --rm kafka-init`.
set -euo pipefail

BOOTSTRAP="${BOOTSTRAP_SERVERS:-kafka:9092}"
PARTITIONS="${TOPIC_PARTITIONS:-3}"
REPLICATION="${TOPIC_REPLICATION_FACTOR:-1}"

TOPICS=(
  bank-transfer-payment-update   # name fixed by the brief
  reservation-status-changed
  refund-requested
  refund-completed
)

for topic in "${TOPICS[@]}"; do
  for name in "${topic}" "${topic}.DLT"; do
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${BOOTSTRAP}" --create --if-not-exists \
      --topic "${name}" --partitions "${PARTITIONS}" --replication-factor "${REPLICATION}"
  done
done

/opt/kafka/bin/kafka-topics.sh --bootstrap-server "${BOOTSTRAP}" --list
