# ADR-0007 Debezium Outbox Event Router as the outbox delivery mechanism

Status: Accepted · Date: 2026-09-26

## Context
ADR-0006 mandates a transactional outbox. Something must move outbox rows to Kafka reliably.

## Decision
Debezium Postgres connector (Kafka Connect, `pgoutput`) with the **Outbox Event Router** SMT, one
connector per service database (`reservation-outbox`, `payment-outbox`). Configuration in
`infra/debezium/*.json`, registered idempotently by a `connect-init` container. Routing by the
`topic` column; key from `aggregate_id`; headers from the metadata columns (contracts/outbox-and-inbox.md).

The Kafka value is the `payload` jsonb text **as stored** (`table.expand.json.payload=false` + `StringConverter`), not
a JSON document rebuilt inside Connect. Found while implementing PR-04 (Debezium 3.6.3 source): expansion infers a
Connect schema from the JSON, so decimals become doubles (`120.00` → `120.0`) and `null` fields are dropped by
default. Both would break the event contracts (amounts at 2 fraction digits; `"previousStatus": null`).

## Consequences
- Zero application code on the publish path and no polling latency; exactly the pattern the assessment asks to see.
- Delivery is at-least-once (connector restarts replay from the last committed LSN); consumers dedupe (ADR-0006).
- **Failure point — replication slot retention**: if a connector is down, Postgres retains WAL for its
  slot indefinitely and the disk fills. Mitigations: slot lag metric and alert (ADR-0013), `max_slot_wal_keep_size`
  set in compose, runbook entry for dropping/recreating a slot.
- **Failure point — connector down while services run**: events queue safely in the outbox/WAL; the
  system degrades to "eventually" with no data loss. README shows this by stopping Connect mid-demo.
- **Failure point — schema drift**: the SMT depends on column names; Flyway must never rename outbox columns
  without a connector update in the same PR.
- Infra weight: Kafka Connect + connector registration in compose. Accepted for the demonstration value.
- The outbox table is never read by the application; rows are purged after 7 days by a scheduled job.

## Alternatives considered
- Polling relay (`@Scheduled` + `FOR UPDATE SKIP LOCKED`, optionally woken by `LISTEN/NOTIFY`):
  simplest, no extra infra, good enough for most teams; rejected here because the reviewer asked to
  see the CDC form and because it adds ~150 lines of relay code that must itself be tested for
  ordering and crash safety. Kept as the documented fallback for environments without Connect.
- Spring Modulith externalized events: elegant but ties the outbox to Modulith's event publication
  registry and delivers via `KafkaTemplate` from the app; rejected.
- Kafka transactions + "read-process-write" (exactly-once semantics): only covers Kafka-to-Kafka;
  the DB write is outside; rejected.
