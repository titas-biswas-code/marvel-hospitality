# ADR-0008 Kafka consumption: acknowledgement, retries, dead-letter topics, poison messages

Status: Accepted · Date: 2026-09-26 · Amended: 2026-09-27 (consumer groups, see "Future")

## Context
Consumers must never lose a payment, never double-apply one, never wedge a partition on a bad message,
and must surface unprocessable messages for humans.

## Decision
- `enable.auto.commit=false`, container `AckMode.MANUAL_IMMEDIATE`: the listener acknowledges only after the
  use case it calls has committed its transaction, so a crash in between redelivers instead of losing the record.
  After a record is dead-lettered, the error handler commits its offset (`commitRecovered`).
- Deserialization is wrapped in `ErrorHandlingDeserializer` so malformed bytes reach the error handler
  as an exception instead of looping the container.
- Error handler: `DefaultErrorHandler(DeadLetterPublishingRecoverer, ExponentialBackOff)` —
  5 attempts, 1s initial, ×2, max 30s. Retries are **blocking** (in-order) because ordering per key
  matters and volumes are small.
- Non-retryable (straight to DLT): `DeserializationException`, `MethodArgumentNotValidException`/
  validation failures, `IllegalArgumentException` from contract violations, `ConversionException`.
- Retryable: everything else (DB unavailable, transient SQL, lock timeouts).
- **Business outcomes are not errors**: an unmatched payment is a successful consumption that produces
  an `UNMATCHED_*` record. Only technical failures go to the DLT.
- DLT naming `<topic>.DLT` (set explicitly: spring-kafka 4 defaults to `<topic>-dlt`), same key and same partition
  number, original headers plus spring-kafka's `kafka_dlt-*` exception headers. No automatic DLT re-consumption;
  `scripts/replay-dlt.sh` and a metric (`kafka.dlt.messages{topic}`) exist instead.
- The policy lives once in `platform/kafka-starter` (ADR-0001); services only declare `@KafkaListener`s.
- Idempotency per ADR-0006 via `processed_message`; the dedupe key per topic is in contracts/events.md.
- Consumer concurrency = partitions (3); one listener container per topic; consumer group = service name.
- Topics are created by infra, not by the apps.

## Consequences
- A poison message delays its partition by the backoff sum (1 + 2 + 4 + 8 = 15 seconds) before landing in the DLT.
- Exactly-once is achieved by DB-side idempotency, not by Kafka transactions.
- Humans must watch the DLT metric; this is an operational commitment stated in the README.
- One group per service means a service's listeners rebalance together: with the classic group protocol a member
  joining or leaving for one topic briefly pauses the service's consumers of its other topics. At these volumes the
  pause is negligible.
- The inbox's `consumer` column is the group id. Renaming a group is therefore not a free operation: the new group
  starts at `auto-offset-reset: earliest`, and because the inbox key `(message_id, consumer)` changed with it, already
  applied messages would be applied again. A rename needs the inbox consumer name decoupled from the group first.

## Alternatives considered
- Non-blocking retries (`@RetryableTopic`): great for high throughput, breaks per-key ordering; rejected.
- Auto-commit: risks losing a message on crash between poll and commit; rejected.
- Skipping bad records silently: violates "never lose a payment"; rejected.
- One consumer group per listener instead of per service: independent rebalances and per-topic lag, but groups do
  not affect correctness (that comes from the inbox and ack-after-commit), the group then no longer names the
  consuming application in tooling, and the switch hits the inbox coupling above; rejected. The incremental protocol
  below gives the rebalance isolation without it.

## Future
- **KIP-848 consumer group protocol** (`group.protocol=consumer`, GA since Kafka 4.0, supported by spring-kafka 4):
  the broker assigns partitions and rebalances only the members that changed, so one listener's churn no longer
  pauses the service's other listeners. It is one consumer setting in `platform/kafka-starter` (and no client-side
  `partition.assignment.strategy`); adopt it when rebalance pauses show up in lag, after testing retry and DLT
  behaviour under it.
