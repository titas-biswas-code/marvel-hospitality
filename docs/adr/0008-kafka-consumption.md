# ADR-0008 Kafka consumption: acknowledgement, retries, dead-letter topics, poison messages

Status: Accepted · Date: 2026-09-26

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

## Alternatives considered
- Non-blocking retries (`@RetryableTopic`): great for high throughput, breaks per-key ordering; rejected.
- Auto-commit: risks losing a message on crash between poll and commit; rejected.
- Skipping bad records silently: violates "never lose a payment"; rejected.
