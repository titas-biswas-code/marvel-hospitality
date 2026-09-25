# ADR-0014 bank-transfer-payment-service scope and the bank simulator

Status: Accepted · Date: 2026-09-26

## Context
The brief defines the `bank-transfer-payment-update` topic but not its producer. In a real hotel group
the corporate bank exposes incoming payments through a statement feed (camt.052/054), an open-banking
API, or a webhook. Somebody must adapt that feed to Marvel's event bus and own refunds.

## Decision
`bank-transfer-payment-service` is Marvel's **bank adapter and payment ledger**:
- Inbound adapter: `POST /bank-transactions` ("the bank webhook"), idempotent on the bank's transaction
  reference. Real feeds (camt import, PSD2 polling) would be additional adapters writing the same ledger.
- Ledger: `bank_transaction` rows with the raw payload in `jsonb`, own `paymentId`.
- Publishes `PaymentReceived` on `bank-transfer-payment-update` via outbox/CDC (ADR-0006/0007), with the
  field names the brief dictates.
- Knows **nothing** about reservations. It does not parse remittance text. Matching is the reservation
  service's business (ADR-0009). This keeps the bounded contexts clean and lets the same service serve
  other consumers of payments later.
- Owns refunds: consumes `refund-requested` (inbox on `refundId`), creates a `refund_instruction` back to
  the original debtor account (this is why the event carries `debtorAccountnumber`), "executes" it (stub:
  immediate success unless the account starts with `FAIL`), publishes `RefundCompleted`.
- `bank-transfer-simulator/` is "the bank": shell scripts (`curl` + `jq`) and Postman requests that obtain
  a service-account token and post transactions. It is not a service and has no build.

## Consequences
- Two services now demonstrate the same transactional pattern; the refund flow demonstrates compensation.
- The E2E id in the remittance (`<10 chars>`) is the SEPA end-to-end identifier the payer supplies; the
  service stores it verbatim inside `remittance_information` and does not interpret it.

## Alternatives considered
- Reservation service consumes the bank feed directly: mixes banking concerns into the booking context; rejected.
- Payment service does the matching and publishes `PaymentMatched`: couples the ledger to reservation
  semantics and contradicts the topic contract the brief fixed; rejected.
