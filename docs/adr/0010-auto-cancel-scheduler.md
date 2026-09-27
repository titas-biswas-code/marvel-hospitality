# ADR-0010 Automatic cancellation scheduler

Status: Accepted · Date: 2026-09-26 · Amended: 2026-09-27 (see "Amendment")

## Context
Bank-transfer reservations whose total is not received two days before the start date must be cancelled
automatically, correctly across restarts and with several instances running.

## Decision
- The deadline is **data**, computed once at creation:
  `paymentDeadlineAt = startDate.atStartOfDay(property.timezone).minusDays(2).toInstant()`.
  "Two days before the start date" therefore means "before local midnight two calendar days earlier".
- A bank-transfer reservation whose deadline is already in the past at creation is rejected with
  `422 BANK_TRANSFER_LEAD_TIME_TOO_SHORT` (you cannot pay by bank transfer for tonight). A decision the
  requirements leave open; alternatives (treat as cash-on-arrival, or immediate deadline) noted in README.
- Job: `@Scheduled(fixedDelayString = "${reservation.auto-cancel.interval:PT60S}")`. It reads due rows in pages
  **without locks**, walking forward by `(payment_deadline_at, id)`:
  ```sql
  SELECT id, payment_deadline_at FROM reservation
   WHERE status = 'PENDING_PAYMENT' AND payment_mode = 'BANK_TRANSFER' AND payment_deadline_at <= :now
     AND (payment_deadline_at, id) > (:afterDeadline, :afterId)
   ORDER BY payment_deadline_at, id LIMIT 100
  ```
  and then claims each row in its **own** transaction (`REQUIRES_NEW`), so one failure does not roll back the
  others:
  ```sql
  SELECT * FROM reservation
   WHERE id = :id AND status = 'PENDING_PAYMENT' AND payment_mode = 'BANK_TRANSFER' AND payment_deadline_at <= :now
   FOR UPDATE SKIP LOCKED
  ```
  A claimed row is cancelled through `Reservation.cancel(PAYMENT_DEADLINE_MISSED)` and the outbox event is written
  in that same transaction. A row that is locked elsewhere, or is no longer due, is skipped and picked up on a later
  run if it is still due then.
- Multi-instance safety comes from the per-row `SKIP LOCKED` claim (an instance never waits for, or cancels
  again, a row another instance holds); restart safety comes
  from the deadline being persisted (nothing is scheduled in memory). No ShedLock, no leader election.
- `Clock` is injected; tests move time instead of sleeping.
- A payment that arrives after cancellation is handled by ADR-0009 (`UNMATCHED_NOT_PENDING` + refund).
- Race: payment consumer and scheduler touching the same row concurrently are serialised by the row lock
  (`FOR UPDATE`) and `@Version`. A row the consumer holds is skipped by the job and re-checked on the next run;
  a consumer that waits for the job's lock then sees `CANCELLED`.

## Consequences
- Cancellation happens within one polling interval of the deadline, never before it.
- The partial index `reservation_deadline_idx` keeps the query O(due rows).
- Metrics: `reservation.autocancel.cancelled` counter (tag `propertyId`), `reservation.autocancel.overdue` gauge
  (rows still due after the last run; above zero means rows are failing or held elsewhere).

## Amendment
The original text locked the whole batch (`SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100`) and then cancelled each
row in `REQUIRES_NEW`. Implemented as written, that deadlocks: the outer transaction keeps the row locks, and the
inner `REQUIRES_NEW` transaction on the same thread waits for them forever. Postgres cannot detect this, because
from its point of view one session is simply idle while holding locks another is waiting for. The page is now read
without locks and `SKIP LOCKED` moved into the per-row claim. That keeps all three guarantees: instances split the
work, each row has its own transaction, and nobody waits on a lock. Holding the batch in one transaction with a
savepoint per row was rejected: a failed row would still keep every other row locked until the whole batch
finished, and savepoints do not work reliably with JPA's transaction manager.

The gauge was renamed from `reservation.autocancel.lag` (age of the oldest overdue row) to
`reservation.autocancel.overdue` (count of rows still due after a run). A count says directly whether rows
are stuck and is easier to alert on.

## Alternatives considered
- Kafka delayed messages / timers: no native delay in Kafka; rejected.
- Quartz with persistent jobs: a second scheduler store for one query; rejected.
- ShedLock leader lock: unnecessary given `SKIP LOCKED`; rejected.
