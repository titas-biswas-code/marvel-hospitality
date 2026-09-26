# ADR-0009 Bank-transfer payment matching rules

Status: Accepted · Date: 2026-09-26

## Context
The bank event carries `transactionDescription = "<10-char E2E id> <8-char reservationId>"`, an amount,
and a debtor account. The brief says "total amount not received" implies partial payments exist.

## Decision
1. Persist **every** payment first (`received_payment`, PK `paymentId`) — matched or not — then classify:

| Condition | Outcome | Effect |
|---|---|---|
| description does not match regex | `UNMATCHED_FORMAT` | stored, reservation null |
| reservationId unknown | `UNMATCHED_UNKNOWN_RESERVATION` | stored |
| reservation not `PENDING_PAYMENT` (cancelled or already confirmed) | `UNMATCHED_NOT_PENDING` | stored; **RefundRequested** for full amount, reason `RESERVATION_CANCELLED` (or `OVERPAYMENT` if confirmed) |
| sum(received) < total | `MATCHED_PARTIAL` | `amountReceived` updated; status event with reason `PARTIAL_PAYMENT_RECEIVED` |
| sum(received) == total | `MATCHED_FULL` | `CONFIRMED` |
| sum(received) > total | `OVERPAID` | `CONFIRMED`; RefundRequested for the surplus, reason `OVERPAYMENT` |

2. Matching is purely on `reservationId`; the E2E id is stored for audit only. Debtor account is not used
   for matching (a parent may pay for a child).
3. Sum-based matching makes the result independent of message order.
4. Amount comparison is exact `BigDecimal` at scale 2; no tolerance.
5. Payments are never applied to a reservation of a *different* property than the one that owns the
   reservationId (ids are global, so this cannot happen; asserted anyway).
6. Unmatched payments are **not** auto-refunded: a typo could still be reconciled by a human; they sit in
   `received_payment` for a reconciliation UI (BONUS endpoint). This is a deliberate business judgement
   and the README says so.

## Consequences
- The reservation service needs only `paymentId`, amount, description; it never calls the payment service.
- `amountReceived` on the reservation is denormalised for the API and for the scheduler; the source of
  truth is `sum(received_payment.amount)` over the reservation's matched payments, recomputed inside the same
  transaction. `UNMATCHED_NOT_PENDING` payments are refunded, so they never count.
- Concurrent payments for one reservation are serialised by a row lock: the consumer loads the reservation
  `SELECT ... FOR UPDATE` before summing. Topic ordering does not help here: the bank topic is keyed by `paymentId`,
  so two payments for one reservation can sit on different partitions and be consumed at the same time. The
  `@Version` column stays as a second line of defence; a conflict it detects is a retryable failure (ADR-0008).

## Alternatives considered
- Fuzzy matching (case-insensitive, whitespace-tolerant, Levenshtein): tempting, but a wrong match
  confirms the wrong room; rejected for automation, fine for a human reconciliation tool.
- Refund all unmatched payments automatically: destroys the chance to fix a typo; rejected.
- Tolerance for small underpayment (bank fees): real-world need; out of scope, documented as configurable future rule.
