# ADR-0010 Automatic cancellation scheduler

Status: Accepted · Date: 2026-09-26

## Context
Bank-transfer reservations whose total is not received two days before the start date must be cancelled
automatically, correctly across restarts and with several instances running.

## Decision
- The deadline is **data**, computed once at creation:
  `paymentDeadlineAt = startDate.atStartOfDay(property.timezone).minusDays(2).toInstant()`.
  "Two days before the start date" therefore means "before local midnight two calendar days earlier".
- A bank-transfer reservation whose deadline is already in the past at creation is rejected with
  `422 BANK_TRANSFER_LEAD_TIME_TOO_SHORT` (you cannot pay by bank transfer for tonight). Logical decision
  the brief leaves open; alternatives (treat as cash-on-arrival, or immediate deadline) noted in README.
- Job: `@Scheduled(fixedDelayString = "${reservation.auto-cancel.interval:PT60S}")`, in batches:
  ```sql
  SELECT ... FROM reservation
   WHERE status = 'PENDING_PAYMENT' AND payment_mode = 'BANK_TRANSFER' AND payment_deadline_at <= :now
   ORDER BY payment_deadline_at FOR UPDATE SKIP LOCKED LIMIT 100
  ```
  Each row is cancelled in its **own** transaction (`REQUIRES_NEW`) so one failure does not roll back a batch;
  cancellation goes through `Reservation.cancel(PAYMENT_DEADLINE_MISSED)` and writes the outbox event.
- Multi-instance safety comes from `SKIP LOCKED` (each instance takes different rows); restart safety comes
  from the deadline being persisted (nothing is scheduled in memory). No ShedLock, no leader election.
- `Clock` is injected; tests move time instead of sleeping.
- A payment that arrives after cancellation is handled by ADR-0009 (`UNMATCHED_NOT_PENDING` + refund).
- Race: payment consumer and scheduler touching the same row concurrently are serialised by the row lock
  (`FOR UPDATE`) and `@Version`; the loser retries or sees the new state.

## Consequences
- Cancellation happens within one polling interval of the deadline, never before it.
- The partial index `reservation_deadline_idx` keeps the query O(due rows).
- Metrics: `reservation.autocancel.cancelled` counter, `reservation.autocancel.lag` gauge (oldest overdue).

## Alternatives considered
- Kafka delayed messages / timers: no native delay in Kafka; rejected.
- Quartz with persistent jobs: a second scheduler store for one query; rejected.
- ShedLock leader lock: unnecessary given `SKIP LOCKED`; rejected.
