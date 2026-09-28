# Postman

Two files: `marvel-hospitality.postman_collection.json` and `local.postman_environment.json`.
New requests are added to this same collection; the files are never replaced.

## Import

In Postman: Import both `marvel-hospitality.postman_collection.json` and `local.postman_environment.json`,
then select the "marvel-hospitality (local)" environment (top right).

## Run order

The whole collection runs top to bottom (Postman: *Run collection*, or newman below), and so does every folder on its
own: each fetches the tokens it needs first, stores them in the environment variable `access_token`, and the
collection-level auth `Bearer {{access_token}}` picks them up. For single requests, run a request from the **Auth**
folder first ("Get token (alice)", "(bob)", "(carol)" or "(bank-simulator, client credentials)").

A few requests in the service folders use an id another request stores ("Get refund by refundId", the notification
requests): run on their own they are skipped with a console note, not failed.

`whoami` in each service folder needs any valid token; `whoami without token → 401` in each service folder
deliberately overrides auth to `noauth` to prove the service is protected.

`room-reservation-service`'s **Reservations** folder needs a token with `reservation:write`/
`reservation:read` for property `AMS01` (alice or bob). Run "Create cash reservation", "Create bank-transfer
reservation" or "Create credit-card reservation (OK-… → 201 CONFIRMED)" first — each saves `reservationId` to the
environment — then "Get reservation". "Reference data (public)" needs no token at all. All three create requests
are re-runnable any number of times: a pre-request script picks a random 2-night stay on its own room (own env
vars `cash_start`/`cash_end`, `bank_start`/`bank_end`, `card_start`/`card_end`), so back-to-back runs never collide
on `409 ROOM_UNAVAILABLE`; the credit-card request also mints a fresh `paymentReference` ("OK-" + `Date.now()`,
env var `card_reference`) each run so it never collides on `409 PAYMENT_REFERENCE_ALREADY_USED` either.

The other two **credit-card** requests need `make up-apps` (it includes `credit-card-payment-service`).
The stub decides by `paymentReference` prefix: `REJ-1` → `422 PAYMENT_REJECTED` (nothing stored, repeatable).
"payment service unavailable → 503" is always a 503 and never books: with the stub up its `SLOW-1` reference
outlasts the read timeout (~6.5 s over three attempts); stop the stub (command in its description) to see the
connection-failure path instead. The **credit-card-payment-service** folder calls the stub directly, without a
token (the spec declares no security, ADR-0011); it uses the `credit_card_url` environment variable.
Its four requests show each outcome of the stub: `OK-123` → `CONFIRMED`, `REJ-1` → `REJECTED`, `ERR-1` → `500`,
and an unknown reference → `404`.

`notification-service`'s **Notifications** folder needs `reservation:read`, and only shows notifications of the
properties in the token (alice has both; bob only AMS01, carol only LIS01). It reads `reservationId` from the
environment, same as the reservation folder above — run a "Create ..." reservation request first. "List notifications
without reservationId → 400 VALIDATION_FAILED" needs only any valid token. "List notifications without
reservation:read → 403" needs a token that lacks the role: run "Get token (bank-simulator, client credentials)" from
**Auth** first (bank-simulator only has `bank:ingest` and `bank:read`). Notifications are produced asynchronously
(reservation outbox → Debezium → Kafka → notification-service), so a freshly booked reservation may show an empty
or short list until that catches up.

## Demo: bank transfer paid in two parts

The folder **"Demo: bank transfer paid in two parts"** is the payment-matching flow end to end, meant for the Collection
Runner (run the folder, top to bottom). It includes its own token requests, so it switches between alice (books,
reads) and the bank-simulator (pays) by itself:

1. alice books room 202 for 2 nights (240.00, `BANK_TRANSFER`) on a random date in the 1000 years from 2030, so the
   folder can be re-run without `409 ROOM_UNAVAILABLE`; saves `reservationId`.
2. The bank pays 120.00 with remittance `1401541457 <reservationId>` → the reservation is still `PENDING_PAYMENT`,
   `amountReceived` 120.00.
3. The bank pays the other 120.00 → `CONFIRMED`, 240.00; `…/payments` lists `MATCHED_PARTIAL` then `MATCHED_FULL`.
4. `notification-service`'s `/notifications` lists this reservation's notifications, oldest first:
   `RESERVATION_CREATED_PENDING_PAYMENT`, `PARTIAL_PAYMENT_RECEIVED`, `RESERVATION_CONFIRMED`.

Payments are applied asynchronously (outbox → Debezium → Kafka → reservation service), so the two reservation
checks and the notifications check retry for up to ~10 s instead of assuming a fixed delay. The **Unmatched
payments** folder lists payments that could not be applied: `GET /unmatched-payments` needs `bank:read` (alice,
bob, carol have it) and shows payments that name no known reservation; `GET /properties/AMS01/unmatched-payments`
shows the property's payments that arrived after a reservation was cancelled or already paid.

The four demo folders below follow the same shape as this one: numbered steps, their own token requests, their own
random dates and their own env-var prefixes (`b1_`, `b2_`, `b3_`, `b4_`) so they never interfere with each other or
with this folder. Each is fully self-contained (Collection Runner or newman, top to bottom) and re-runnable any
number of times.

## Demo: booking rules

The folder **"Demo: booking rules"** runs the creation-time rules end to end:

1. alice books room 102 (SMALL, AMS01) on a random 2-night stay; the exact same request repeated, and an
   overlapping one, both answer `409 ROOM_UNAVAILABLE`: the database's exclusion constraint refuses the overlap (ADR-0005).
2. The same room and dates with `roomSegment: MEDIUM` → `422 ROOM_SEGMENT_MISMATCH` (checked before availability).
3. `endDate == startDate` → `400 VALIDATION_FAILED`; `roomNumber: "999"` → `404 ROOM_NOT_FOUND`; a bank-transfer
   stay starting tomorrow → `422 BANK_TRANSFER_LEAD_TIME_TOO_SHORT` (less than the required two-day lead).
4. The booked reservation looked up under `LIS01` instead of `AMS01` → `404 RESERVATION_NOT_FOUND`.
5. bob (property `AMS01` only) booking at `LIS01` → `403 FORBIDDEN_PROPERTY`; carol (no `reservation:write`)
   booking at `LIS01` → `403 FORBIDDEN`: the role is checked before the property.
6. alice books room 401 (EXTRA_LARGE) by credit card with a fresh `OK-…` reference → `201 CONFIRMED`; the same
   reference on different dates → `409 PAYMENT_REFERENCE_ALREADY_USED`; a `REJ-…` reference →
   `422 PAYMENT_REJECTED`.

Every error assertion checks the status code, `Content-Type: application/problem+json` and the `code` extension.

## Demo: unmatched payments

The folder **"Demo: unmatched payments"** shows the reconciliation view for money that never resolved to
a reservation (ADR-0009): the bank-simulator posts a well-formed remittance naming a reservationId that does not
exist (`202`, outcome `UNMATCHED_UNKNOWN_RESERVATION`) and an unparseable one (`202`, outcome `UNMATCHED_FORMAT`).
`GET /unmatched-payments` (role `bank:read`, no property check — the bank topic carries no `propertyId`) retries
until both show up, `reservationId`/`propertyId` null and unrefunded: a human might still reconcile a typo, so
these are never auto-refunded, unlike the property-scoped view below.

## Demo: refunds

The folder **"Demo: refunds"** runs the automatic-refund paths end to end:

1. alice books room 201 (MEDIUM, 240.00, `BANK_TRANSFER`); the bank pays 250.00. The reservation confirms with
   `amountReceived` 250.00 (the sum actually received, not capped to the total); the payment's outcome is
   `OVERPAID` with a `COMPLETED` refund of the 10.00 surplus, reason `OVERPAYMENT`. `GET /refunds/{refundId}` on
   `bank-transfer-payment-service` shows the same refund as `EXECUTED` (its own status enum differs from the
   reservation-side `COMPLETED`).
2. The bank then pays 50.00 more for the now-`CONFIRMED` reservation: it cannot be matched, so it is
   `UNMATCHED_NOT_PENDING` and refunded in full automatically; `GET /properties/AMS01/unmatched-payments` shows it.
3. alice books room 301 (LARGE, 360.00); the bank overpays it by 10.00 from a debtor account starting `FAIL`, which
   the refund payout stub always rejects (`CREDITOR_ACCOUNT_REJECTED`). The payment is `OVERPAID` with a `FAILED`
   refund on both services' views — a failed refund needs a human, but stays visible rather than silently lost.

## Demo: notifications

The folder **"Demo: notifications"** shows the notification consumer beyond the two-part demo's sequence:
a cash and a credit-card confirmation each render their payment method's own wording ("to be paid in cash at the
property" / "paid by credit card", and the card text is asserted to never read "EUR 0.00"); a reservationId that
was never booked, and a reservation outside the caller's token properties, both come back `200 []` rather than an
error (the endpoint filters by property, since its path names none); and a token without `reservation:read`
(bank-simulator) gets `403 FORBIDDEN`. The `PENDING_PAYMENT` / `PARTIAL_PAYMENT_RECEIVED` / `CONFIRMED` bank-transfer
sequence is already covered by the two-part demo's step 12, so it is not repeated here.

Auto-cancel has no Postman demo: seeing it fire needs the stored payment deadline moved backwards in the
database, which is out of scope for an HTTP-only collection. See the root README's
[5-minute demo](../README.md#5-minute-demo) (step 7) instead.

## Newman (CLI)

After `make up-apps` (Windows: `.\marvel up-apps`), everything at once:

```
npx --yes newman run postman/marvel-hospitality.postman_collection.json -e postman/local.postman_environment.json
```

or one folder, e.g.:

```
npx --yes newman run postman/marvel-hospitality.postman_collection.json \
  -e postman/local.postman_environment.json --folder "Demo: bank transfer paid in two parts"
```
