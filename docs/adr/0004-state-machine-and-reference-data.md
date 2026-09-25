# ADR-0004 Reservation state machine in code; statuses persisted as text; reference-data endpoint

Status: Accepted · Date: 2026-09-26

## Context
Statuses `PENDING_PAYMENT`, `CONFIRMED`, `CANCELLED` are fixed by the brief. A future UI should not
hardcode them. Someone asked whether statuses should be configurable or DB-driven.

## Decision
- `ReservationStatus`, `PaymentMode`, `RoomSegment`, `PaymentMatchOutcome`, `RefundReason`,
  `CancellationReason` are **Java enums**. The transition table lives in the `Reservation` aggregate:
  ```
  (new)           -> PENDING_PAYMENT   bank transfer created
  (new)           -> CONFIRMED         cash, or credit card confirmed
  PENDING_PAYMENT -> CONFIRMED         full amount received
  PENDING_PAYMENT -> CANCELLED         payment deadline missed
  anything else   -> IllegalStateTransitionException
  ```
  `Reservation.confirm(clock)`, `Reservation.cancel(reason, clock)`, `Reservation.recordPayment(amount)`
  are the only mutators; each records a domain event the application layer turns into outbox rows.
- Persisted as `varchar` with a `CHECK`; never ordinal; never a DB enum type.
- `GET /reference-data` returns all enum values via `Enum.values()` so any UI seeds itself from the
  service. Adding a value is a code change + migration widening the `CHECK`, on purpose.
- Payment modes plug in through a `PaymentModeHandler` strategy (`mode()`, `handle(command) -> InitialOutcome`)
  registered in a `Map<PaymentMode, PaymentModeHandler>`; adding a mode is one class.
- No Spring Statemachine: three states and four transitions do not justify a framework.

## Consequences
- The scheduler and the matcher compile against the enum; a "configurable" status would have no meaning
  to them anyway. The UI concern (not hardcoding) is solved by the endpoint, not by making semantics dynamic.
- Room segment *rates* are data (`room_rate` table) because they vary per property and change often;
  the segment names themselves are still an enum because the API contract uses them.

## Alternatives considered
- Status table in DB with FK: adds joins and a second source of truth without removing any code branch; rejected.
- Spring Statemachine: rejected as disproportionate.
