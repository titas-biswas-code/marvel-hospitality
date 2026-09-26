# ADR-0011 Credit-card payment integration and resilience policy

Status: Accepted · Date: 2026-09-26

## Context
For credit-card reservations the brief says: call `credit-card-payment-service` to retrieve the payment
status; confirm the room if `CONFIRMED`, otherwise throw an error. The provided OpenAPI spec has defects.

## Decision
- Keep the flow **synchronous** as the brief states. Sequence inside the `CreditCardPaymentModeHandler`:
  1. validate request: shape, stay and segment rules, payment reference not used yet, lock-free availability read
     (no DB tx held);
  2. call `POST /payment-status` (read-only, idempotent);
  3. on `CONFIRMED`: open a short tx, insert `CONFIRMED` reservation (+ outbox), commit;
  4. on `REJECTED` or `404`: `422 PAYMENT_REJECTED`, nothing persisted;
  5. on timeout / 5xx / circuit open: `503 PAYMENT_SERVICE_UNAVAILABLE` with `Retry-After`, nothing persisted.
  A DB transaction is never held across the remote call.
- Client generated from the **corrected** spec with `openapi-generator` (Java, `restclient` library) at
  build time; defects corrected and listed in the spec header and `docs/credit-card-spec-defects.md` (the spec as
  provided is kept unchanged in `docs/contracts/credit-card-payment-api.original.yaml` for diffing; the corrections
  change nothing on the wire). The corrected spec lives with the code: the provider's copy in
  `credit-card-payment-service/src/main/resources/openapi/` and the consumer's identical copy in
  `room-reservation-service/src/main/resources/openapi/`, kept equal by `make check-contracts`, so no build reads
  from `docs/`. Defects:
  malformed server URL, `format: enum` misuse, driving-licence description leftover, `datetime` format.
- Resilience4j on the client: connect timeout 1s, read timeout 2s; retry 2 attempts on 5xx/IOException
  (not on 4xx), 200ms backoff; circuit breaker sliding window 20, failure threshold 50%, open 10s, half-open 3;
  no bulkhead (tiny service). Timeout is enforced by the HTTP client, not `TimeLimiter`, because the call is blocking.
- If the ambiguous case (timeout after the payment service may have answered) worries a reviewer: the call
  is a status *retrieval*, so a retry is safe.
- The spec declares no security; the stub has none and the client sends none. ADR-0012 notes that a real
  deployment would use a service-account token.

## Consequences
- The API contract for `CREDIT_CARD` is simple and honest: you get `201 CONFIRMED` or an error.
- Before the call, the stay/segment rules and a lock-free availability read run, so a request that is bound to
  fail (bad dates, room already booked) never reaches the payment service. The exclusion constraint stays the
  final word: a room taken between that read and the insert still yields `409 ROOM_UNAVAILABLE` after a confirmed
  payment. The status call itself charges nothing, but the guest *has* paid (through the card flow that produced the
  `paymentReference`) and has no reservation. The same holds for any other failure of the short transaction.
  Such cases are logged at WARN with `paymentReference` and `propertyId` ("needs manual reconciliation"); the
  provided card-payment spec has no void/refund operation, so automatic compensation is out of reach here. The
  asynchronous saga (ADR-0006) is where this would be solved properly.
- One confirmed card payment backs at most one reservation: the partial unique index
  `reservation_credit_card_payment_reference_uq` on `payment_reference WHERE payment_mode = 'CREDIT_CARD'` (V2) is
  mapped by name to `409 PAYMENT_REFERENCE_ALREADY_USED`, and the same predicate is read before the call so a reused
  reference never reaches the payment service. Global rather than per property, because there is one card-payment
  service for the whole corporation. A request that loses this race is not "paid but no reservation" (its payment
  backs the winner), so it is not logged for reconciliation.
- Generated client code is not committed (`build/generated`), which keeps the diff reviewable.

## Alternatives considered
- Persist `PENDING_PAYMENT` first, then call, then update: leaves dangling rows on failure and holds the
  room during a call; rejected.
- Asynchronous saga (`PENDING_AUTHORISATION` + `PaymentVerified` event): the scalable form, documented as
  the upgrade path in ADR-0006; rejected for the brief's explicit synchronous wording.
- Hand-written client instead of generation: fewer moving parts, but generating from the corrected spec
  proves the spec was actually read; kept generation.
