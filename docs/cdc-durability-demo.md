# CDC durability demo (outbox → Debezium → Kafka)

Shows that a payment is never lost even if Kafka Connect is down when it happens — it just arrives late.

1. `make up-apps`.
2. Stop Kafka Connect: `docker compose -f infra/docker-compose.yml --env-file infra/.env stop connect`.
3. Post two bank transactions while Connect is down:
   `./bank-transfer-simulator/scripts/post-bank-transaction.sh --reservation P4145478 --amount 120`
   (twice, with different `--ref`s, or via `pay-in-full.sh` against two reservations).
4. Confirm both landed in Postgres despite Connect being down:
   `docker compose -f infra/docker-compose.yml --env-file infra/.env exec postgres \
     psql -U payment -d payment -c "select aggregate_id, event_type, created_at from outbox_event order by created_at desc limit 5"`.
5. Confirm they are **not** yet on Kafka: open kafka-ui (http://localhost:8090), topic
   `bank-transfer-payment-update` — message count hasn't moved.
6. Restart Connect: `docker compose -f infra/docker-compose.yml --env-file infra/.env start connect`.
   Within a few seconds both messages appear on the topic, each with key = `paymentId` and a `propertyId` header
   whose value is null (the bank topic has no property).

Why this works: the outbox row commits in the same Postgres transaction as the ledger row, so step 3 succeeds and
is durable whatever state Connect is in. Debezium does not poll the table; it reads the write-ahead log through a
replication slot and, on restart, resumes from the slot's last confirmed position, so nothing in between is
skipped. Postgres keeps the WAL the stopped slot still needs, up to `max_slot_wal_keep_size=1GB`
(`infra/docker-compose.yml`): beyond that Postgres invalidates the slot to protect its disk, and the connector must
be re-created (runbook in `infra/README.md`). Delivery is at-least-once, so consumers deduplicate (inbox,
ADR-0006); ordering is guaranteed per key (`paymentId`), not across payments.
