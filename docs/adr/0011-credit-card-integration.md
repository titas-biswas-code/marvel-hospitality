# ADR-0011 Credit-card payment integration and resilience policy

Status: Accepted · Date: 2026-09-26

## Context
For credit-card reservations the brief says: call `credit-card-payment-service` to retrieve the payment
status; confirm the room if `CONFIRMED`, otherwise throw an error. The provided OpenAPI spec has defects.

## Decision
- Keep the flow **synchronous** as the brief states. Sequence inside the `CreditCardPaymentModeHandler`:
  1. validate request (no DB tx yet);
  2. call `POST /payment-status` (read-only, idempotent);
  3. on `CONFIRMED`: open a short tx, insert `CONFIRMED` reservation (+ outbox), commit;
  4. on `REJECTED` or `404`: `422 PAYMENT_REJECTED`, nothing persisted;
  5. on timeout / 5xx / circuit open: `503 PAYMENT_SERVICE_UNAVAILABLE` with `Retry-After`, nothing persisted.
  A DB transaction is never held across the remote call.
- Client generated from the **corrected** spec with `openapi-generator` (Java, `restclient` library) at
  build time; defects corrected and listed in the spec header and README:
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
- Room availability is checked after the payment check; a `409` after a confirmed payment is possible
  and surfaces as such — acceptable since the status call does not charge anything.
- Generated client code is not committed (`build/generated`), which keeps the diff reviewable.

## Alternatives considered
- Persist `PENDING_PAYMENT` first, then call, then update: leaves dangling rows on failure and holds the
  room during a call; rejected.
- Asynchronous saga (`PENDING_AUTHORISATION` + `PaymentVerified` event): the scalable form, documented as
  the upgrade path in ADR-0006; rejected for the brief's explicit synchronous wording.
- Hand-written client instead of generation: fewer moving parts, but generating from the corrected spec
  proves the spec was actually read; kept generation.
