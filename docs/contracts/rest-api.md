# REST API contracts

All services: JSON, `ProblemDetail` errors (RFC 9457) with `type = https://marvel-hospitality/problems/<code>`
and extension property `code`. Swagger UI at `/swagger-ui.html`, OpenAPI at `/v3/api-docs`.
Actuator `/actuator/health` (liveness+readiness groups), `/actuator/prometheus` if enabled.
All business endpoints require a bearer JWT (from PR-01 onwards).

## room-reservation-service (port 8080)

### POST /properties/{propertyId}/reservations
Creates and, depending on payment mode, confirms a reservation. Roles: `reservation:write` and
`propertyId ∈ token.properties`.

Request:
```json
{
  "customerName": "Ada Lovelace",
  "roomNumber": "101",
  "startDate": "2026-10-10",
  "endDate": "2026-10-12",
  "roomSegment": "MEDIUM",
  "paymentMode": "BANK_TRANSFER",
  "paymentReference": "optional; REQUIRED when paymentMode = CREDIT_CARD"
}
```
Rules:
- `customerName` 1..200 chars. `roomNumber` must exist in `room` for the property, else `404 ROOM_NOT_FOUND`.
- `roomSegment` must equal the room's segment, else `422 ROOM_SEGMENT_MISMATCH`.
- `startDate >= today` in the property's timezone; `endDate > startDate`; nights = `endDate - startDate`,
  `1 <= nights <= 30` else `400 VALIDATION_FAILED` (details list the field).
- `paymentReference` required for `CREDIT_CARD`; stored as-is for all modes.
- `BANK_TRANSFER` and `paymentDeadlineAt <= now` → `422 BANK_TRANSFER_LEAD_TIME_TOO_SHORT`.
- Room overlap → `409 ROOM_UNAVAILABLE`.
- `CREDIT_CARD` with a `paymentReference` that already backs a reservation (any property) →
  `409 PAYMENT_REFERENCE_ALREADY_USED` (nothing persisted; checked before calling the payment service, enforced by a
  unique index). Cash and bank-transfer references may repeat.
- Credit card `REJECTED` or `404` from payment service → `422 PAYMENT_REJECTED` (nothing persisted).
- Credit card timeout / 5xx / circuit open → `503 PAYMENT_SERVICE_UNAVAILABLE`, `Retry-After: 5` (nothing persisted).

Response `201 Created`, `Location: /properties/{propertyId}/reservations/{reservationId}`:
```json
{
  "reservationId": "P4145478",
  "propertyId": "AMS01",
  "status": "PENDING_PAYMENT",
  "customerName": "Ada Lovelace",
  "roomNumber": "101",
  "roomSegment": "MEDIUM",
  "startDate": "2026-10-10",
  "endDate": "2026-10-12",
  "nights": 2,
  "paymentMode": "BANK_TRANSFER",
  "paymentReference": null,
  "totalAmount": 240.00,
  "amountReceived": 0.00,
  "currency": "EUR",
  "paymentDeadlineAt": "2026-10-07T22:00:00Z",
  "bankTransferInstructions": "Transfer 240.00 EUR to NL00MARV0000000001 with description '<your E2E id> P4145478'",
  "createdAt": "2026-09-26T10:00:00Z",
  "updatedAt": "2026-09-26T10:00:00Z"
}
```
`paymentDeadlineAt` and `bankTransferInstructions` are `null` unless `BANK_TRANSFER`.

### GET /properties/{propertyId}/reservations/{reservationId}
Role `reservation:read`. Same body as above. `404 RESERVATION_NOT_FOUND` (also when it exists under another property).

### GET /properties/{propertyId}/reservations/{reservationId}/payments
Role `reservation:read`. `404 RESERVATION_NOT_FOUND` (also when it exists under another property). List of
`received_payment` rows for the reservation, most recent last:
```json
[
  {
    "paymentId": "5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f",
    "reservationId": "P4145478",
    "propertyId": "AMS01",
    "amount": 120.00,
    "currency": "EUR",
    "outcome": "MATCHED_PARTIAL",
    "transactionDescription": "1401541457 P4145478",
    "debtorAccountNumber": "NL91ABNA0417164300",
    "receivedAt": "2026-10-01T09:15:02Z"
  }
]
```
`reservationId`/`propertyId` are only `null` for the two outcomes below that never resolve to a reservation.

### GET /reference-data
No auth. Seeds UIs so they never hardcode enums:
```json
{
  "reservationStatuses": ["PENDING_PAYMENT","CONFIRMED","CANCELLED"],
  "paymentModes": ["CASH","BANK_TRANSFER","CREDIT_CARD"],
  "roomSegments": ["SMALL","MEDIUM","LARGE","EXTRA_LARGE"],
  "paymentMatchOutcomes": ["MATCHED_PARTIAL","MATCHED_FULL","OVERPAID","UNMATCHED_FORMAT","UNMATCHED_UNKNOWN_RESERVATION","UNMATCHED_NOT_PENDING"],
  "refundReasons": ["OVERPAYMENT","RESERVATION_CANCELLED"],
  "cancellationReasons": ["PAYMENT_DEADLINE_MISSED"]
}
```
Values come from the Java enums via `Enum.values()` (single source of truth).

### GET /properties/{propertyId}/unmatched-payments
Role `reservation:read` + property check (`404 PROPERTY_NOT_FOUND` if the property does not exist). Reconciliation
view of that property's `UNMATCHED_NOT_PENDING` payments: money that arrived for one of its reservations after the
reservation stopped awaiting payment (cancelled, or already confirmed by another payment). These are refunded
automatically (a `RefundRequested` is raised for the full amount, ADR-0009); this endpoint just lets staff follow
up on why they happened. Same response shape as `.../payments` above.

### GET /unmatched-payments
Role `bank:read`, **no property check**. Reconciliation view of `UNMATCHED_FORMAT` and `UNMATCHED_UNKNOWN_RESERVATION`
payments: the bank's transaction event carries no `propertyId`, so a payment that never resolved to a reservation
belongs to no property and cannot be property-checked. It is guarded by `bank:read` instead — the same role that
already grants property-less bank data (`GET /bank-transactions/{paymentId}` on the payment service). These rows are
**not** refunded automatically: a typo in the transfer description could still be reconciled by a human (ADR-0009).
Same response shape as `.../payments` above, with `reservationId` and `propertyId` both `null`.

Error codes: `VALIDATION_FAILED` 400, `ROOM_NOT_FOUND` 404, `RESERVATION_NOT_FOUND` 404,
`PROPERTY_NOT_FOUND` 404, `ROOM_UNAVAILABLE` 409, `PAYMENT_REFERENCE_ALREADY_USED` 409, `ROOM_SEGMENT_MISMATCH` 422,
`BANK_TRANSFER_LEAD_TIME_TOO_SHORT` 422, `PAYMENT_REJECTED` 422, `PAYMENT_SERVICE_UNAVAILABLE` 503,
`UNAUTHENTICATED` 401, `FORBIDDEN` 403, `FORBIDDEN_PROPERTY` 403, `INTERNAL_ERROR` 500.

## bank-transfer-payment-service (port 8081)

### POST /bank-transactions  — "the bank webhook"
Role `bank:ingest` (service account `bank-simulator`). Idempotent on `bankTransactionRef`.
```json
{
  "bankTransactionRef": "BANK-TX-000123",
  "debtorAccountNumber": "NL91ABNA0417164300",
  "debtorName": "A. Lovelace",
  "amount": 120.00,
  "currency": "EUR",
  "remittanceInformation": "1401541457 P4145478",
  "bookedAt": "2026-10-01T09:15:00Z"
}
```
- New → `202 Accepted` `{ "paymentId": "...", "bankTransactionRef": "...", "status": "PUBLISHED" }`
- Same `bankTransactionRef` again → `200 OK` with the existing paymentId (no new event).
- `currency != EUR` → `422 UNSUPPORTED_CURRENCY`. `amount <= 0` → `400 VALIDATION_FAILED`.

### GET /bank-transactions/{paymentId}   role `bank:read`
```json
{
  "paymentId": "5c0c1e4e-3d2a-4b6f-9c1e-0a1b2c3d4e5f",
  "bankTransactionRef": "BANK-TX-000123",
  "debtorAccountNumber": "NL91ABNA0417164300",
  "debtorName": "A. Lovelace",
  "amount": 120.00,
  "currency": "EUR",
  "remittanceInformation": "1401541457 P4145478",
  "bookedAt": "2026-10-01T09:15:00Z",
  "receivedAt": "2026-10-01T09:15:02Z"
}
```
- Unknown `paymentId` → `404 BANK_TRANSACTION_NOT_FOUND`; `paymentId` not a UUID → `400 VALIDATION_FAILED`.

### GET /refunds/{refundId}               role `bank:read`  → `{ refundId, paymentId, amount, reason, status, createdAt, executedAt }`

Error codes: `VALIDATION_FAILED` 400, `UNAUTHENTICATED` 401, `FORBIDDEN` 403, `BANK_TRANSACTION_NOT_FOUND` 404,
`UNSUPPORTED_CURRENCY` 422, `INTERNAL_ERROR` 500.

## credit-card-payment-service (port 9090, base path `/credit-card-payment-api`)
Implements the corrected spec (`credit-card-payment-service/src/main/resources/openapi/credit-card-payment-api.yaml`) exactly. Deterministic stub behaviour keyed on `paymentReference`:
- prefix `OK`  → 200 `CONFIRMED`
- prefix `REJ` → 200 `REJECTED`
- prefix `SLOW`→ sleeps 5 s then 200 `CONFIRMED` (drives the timeout test)
- prefix `ERR` → 500
- otherwise    → 404
No auth (the brief's spec has none; noted in ADR-0011).

## notification-service (port 8082)
### GET /notifications?reservationId=P4145478  role `reservation:read` — lists rendered notifications (demo/verification only).
