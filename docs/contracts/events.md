# Event contracts

Style: **flat JSON values** (no envelope) so the brief-defined topic and our topics look alike;
metadata travels in **Kafka headers**. All values are UTF-8 JSON, all timestamps ISO-8601 UTC,
all amounts decimal numbers with 2 fraction digits (never floats in code: `BigDecimal`).

## Headers on every message our services produce
| Header | Value |
|---|---|
| `eventId` | UUID of the outbox row (dedupe key) |
| `eventType` | e.g. `ReservationStatusChanged`, `PaymentReceived`, `RefundRequested`, `RefundCompleted` |
| `eventVersion` | `1` |
| `producer` | service name |
| `occurredAt` | ISO-8601 UTC |
| `propertyId` | the property; on the bank topic, where the aggregate has none, the header is present with a null value |
| `traceparent` | W3C trace context, propagated by Micrometer tracing |

Debezium Outbox Event Router places these from outbox columns (see `outbox-and-inbox.md`).

## Topic: `bank-transfer-payment-update` (defined by the brief — do not change field names)
- Producer: `bank-transfer-payment-service`. Consumer: `room-reservation-service`.
- Key: `paymentId`. Partitions: 3. Retention: default.
- Value (field names verbatim from the brief, including the odd `debtorAccountnumber`):
```json
{
  "paymentId": "5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f",
  "debtorAccountnumber": "NL91ABNA0417164300",
  "amountReceived": 120.00,
  "transactionDescription": "1401541457 P4145478"
}
```
- Currency is implicitly EUR (brief has none). `eventType` header = `PaymentReceived`.

## Topic: `reservation-status-changed`
- Producer: `room-reservation-service`. Consumers: `notification-service` (and anyone else).
- Key: `reservationId`. Partitions: 3.
```json
{
  "reservationId": "P4145478",
  "propertyId": "AMS01",
  "customerName": "Ada Lovelace",
  "roomNumber": "101",
  "startDate": "2026-10-10",
  "endDate": "2026-10-12",
  "paymentMode": "BANK_TRANSFER",
  "previousStatus": null,
  "status": "PENDING_PAYMENT",
  "reason": null,
  "totalAmount": 240.00,
  "amountReceived": 0.00,
  "currency": "EUR",
  "paymentDeadlineAt": "2026-10-07T22:00:00Z",
  "occurredAt": "2026-09-26T10:00:00Z"
}
```
- Emitted on: creation (previousStatus null), PENDING_PAYMENT→CONFIRMED (reason `PAYMENT_RECEIVED`),
  PENDING_PAYMENT→CANCELLED (reason `PAYMENT_DEADLINE_MISSED`).
- Also emitted with unchanged `status` and reason `PARTIAL_PAYMENT_RECEIVED` when a partial payment lands
  (customers want to know money arrived). `previousStatus == status` in that case.

## Topic: `refund-requested`
- Producer: `room-reservation-service`. Consumer: `bank-transfer-payment-service`.
- Key: `paymentId` (so all refunds of one payment are ordered).
```json
{
  "refundId": "d3b0…",
  "paymentId": "5c0c…",
  "reservationId": "P4145478",
  "propertyId": "AMS01",
  "amount": 30.00,
  "currency": "EUR",
  "reason": "OVERPAYMENT",
  "requestedAt": "2026-10-01T09:16:00Z"
}
```
- `reason ∈ {OVERPAYMENT, RESERVATION_CANCELLED}`. `reservationId` is `null` never (unmatched payments
  are not refunded automatically — ADR-0009).

## Topic: `refund-completed`
- Producer: `bank-transfer-payment-service`. Consumer: `room-reservation-service`.
- Key: `paymentId`.
```json
{
  "refundId": "d3b0…",
  "paymentId": "5c0c…",
  "reservationId": "P4145478",
  "propertyId": "AMS01",
  "amount": 30.00,
  "currency": "EUR",
  "status": "COMPLETED",
  "failureReason": null,
  "completedAt": "2026-10-01T09:16:05Z"
}
```
- `status ∈ {COMPLETED, FAILED}`. The stub always completes unless `debtorAccountNumber` starts with `FAIL`.

## Dead-letter topics
- `<topic>.DLT`, same key, original headers plus spring-kafka's `kafka_dlt-*` exception headers.
- Nobody consumes DLTs automatically. README documents how to inspect (kafka-ui / console consumer)
  and how to replay (`scripts/replay-dlt.sh`, BONUS).

## Consumer groups
| Group | Service | Topics |
|---|---|---|
| `room-reservation-service` | reservation | `bank-transfer-payment-update`, `refund-completed` |
| `bank-transfer-payment-service` | payment | `refund-requested` |
| `notification-service` | notification | `reservation-status-changed` |

## Topic creation
Topics are created by an `infra/kafka-init` one-shot container (`kafka-topics.sh --create --if-not-exists`),
not by the applications (`spring.kafka.admin.auto-create=false`). Tests create their own via `@EmbeddedKafka`-free
Testcontainers + `KafkaAdmin` in test config.
