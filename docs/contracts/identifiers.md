# Identifiers

## propertyId
- Short uppercase code, `^[A-Z]{3}[0-9]{2}$`, e.g. `AMS01`, `RTM01`. Seeded in `property` table.
- Appears in every REST path that touches property-scoped resources, every DB row of property-scoped
  tables, every event value and header that our services produce.

## reservationId (business id, the one on the wire)
- **Exactly 8 characters**, fixed by the bank-transfer remittance format in the brief.
- Format: `P` + 7 characters from Crockford base32 alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ`
  (no I, L, O, U). Example `P4145478`.
- **Globally unique across all properties** (the bank topic carries no property). Enforced by a
  unique index; generator uses `SecureRandom`, retries on collision (max 5, then fail loudly).
- Reservation rows also have an internal `id uuid` primary key. Only `reservationId` leaves the service.

## paymentId
- UUID string, minted by `bank-transfer-payment-service` when a bank transaction is ingested.
- Is the idempotency key of `bank-transfer-payment-update` messages.

## bankTransactionRef
- The bank's own reference for a transaction (opaque string, ≤ 64 chars). Idempotency key of the
  ingest endpoint. One bankTransactionRef → exactly one paymentId.

## refundId
- UUID string, minted by `room-reservation-service` when a refund is requested.
- Idempotency key of both `refund-requested` and `refund-completed`.

## eventId
- UUID string per outbox row. Carried as Kafka header `eventId`. Consumers that dedupe by eventId
  (notification-service) use it as the `processed_message` key.

## Remittance / transactionDescription
- `<E2E id: 10 chars><single space><reservationId: 8 chars>`, e.g. `1401541457 P4145478`.
- Matching regex (anchored, after `trim()`): `^(\S{10}) (P[0-9A-HJKMNP-TV-Z]{7})$`
- Anything else → `UNMATCHED_FORMAT`. No fuzzy matching in this assignment (ADR-0009).
