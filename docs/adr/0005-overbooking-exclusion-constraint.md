# ADR-0005 Overbooking prevention with an exclusion constraint

Status: Accepted · Date: 2026-09-26

## Context
Two concurrent requests for the same room and overlapping nights must not both succeed. The brief
does not mention it, but "production-ready" does. Optimistic locking cannot help: on insert there is
no row to version. Application-level checks (`SELECT` then `INSERT`) race unless serialised.

## How an exclusion constraint works
`UNIQUE` means "no two rows for which `=` holds on all listed columns". `EXCLUDE` generalises the
operator per column: "no two rows for which *every* listed operator returns true". Ours:
```sql
EXCLUDE USING gist (property_id WITH =, room_number WITH =, stay WITH &&) WHERE (status <> 'CANCELLED')
```
It is enforced through a GiST index. GiST cannot natively index scalar `=`, which is what the
`btree_gist` extension adds, so scalar equality and range overlap (`&&`) share one index.
On every insert/update Postgres probes that index. If a conflicting row belongs to an **uncommitted**
transaction, the second transaction **waits**; on commit of the first it fails with SQLSTATE `23P01`,
on rollback it proceeds. This is correct at every isolation level with no application locking.
`daterange(start_date, end_date, '[)')` is half-open, so a check-out day equals the next check-in day.
The `WHERE` makes it a partial index: cancelled rows free the room and do not bloat the index.

## Decision
Use the constraint above as the single source of truth for availability. Map `23P01` on constraint
`reservation_no_overlap` to `409 ROOM_UNAVAILABLE`. Keep a small `FOR UPDATE` pre-check **out** —
it would be redundant.

## Consequences and failure points
- Postgres-only; tests run on real Postgres (ADR-0003).
- Error mapping must be by constraint name/SQLSTATE; a blanket `DataIntegrityViolationException → 409`
  would hide FK and CHECK failures behind the wrong status.
- Models "this room, these nights". If the business moves to "reserve a segment, assign a room at
  check-in" the guarantee becomes a capacity count and needs a different design (counter row + `FOR UPDATE`,
  or an allocation table). Stated limitation.
- Soft holds/waitlists must be excluded from the predicate or they block real bookings.
- GiST writes are heavier than btree; negligible at hotel volumes.
- Dates are civil `date`s in the property's timezone; no time-of-day overlap semantics (check-in/out
  times are a policy, not part of availability).

## Alternatives considered
- Optimistic locking on a `room` row (bump version on every booking): serialises all bookings of a room
  including non-overlapping ones and still needs the overlap query; rejected.
- `SELECT ... FOR UPDATE` on the room row then overlap query: works, but relies on every code path
  remembering to lock; rejected in favour of a constraint the DB enforces unconditionally.
- Serializable isolation: correct but retry-heavy and easy to get wrong across pools; rejected.
