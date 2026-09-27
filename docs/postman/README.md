# Postman

Populated from PR-01 onwards: `marvel-hospitality.postman_collection.json` and `local.postman_environment.json`.
Later PRs only append requests to this same collection — the files are not replaced.

## Import

In Postman: Import both `marvel-hospitality.postman_collection.json` and `local.postman_environment.json`,
then select the "marvel-hospitality (local)" environment (top right).

## Run order

1. Run a request from the **Auth** folder ("Get token (alice)", "(bob)", "(carol)", or
   "(bank-simulator, client credentials)"). Its test script parses the response and stores the token in
   the environment variable `access_token`.
2. Run any other request — the collection-level auth is `Bearer {{access_token}}`, so it picks up the
   token automatically. The Auth folder's own requests are `noauth` (they are what obtains the token).

`whoami` in each service folder needs any valid token; `whoami without token → 401` in
`room-reservation-service` deliberately overrides auth to `noauth` to prove the endpoint is protected.

`room-reservation-service`'s **Reservations** folder (PR-02) needs a token with `reservation:write`/
`reservation:read` for property `AMS01` (alice or bob). Run "Create cash reservation" or "Create
bank-transfer reservation" first — either saves `reservationId` to the environment — then "Get reservation".
"Reference data (public)" needs no token at all. The two create requests use fixed far-future dates on
different rooms so they can be re-run without colliding with each other, but re-running the *same* one twice
without changing its dates hits `409 ROOM_UNAVAILABLE` on the second run.

The three **credit-card** requests (PR-03) need `make up-apps` (it includes `credit-card-payment-service`). The
stub decides by `paymentReference` prefix: `OK-123` → `201 CONFIRMED` (saves `reservationId`; a second run is
`409 PAYMENT_REFERENCE_ALREADY_USED`, because one card payment backs one reservation), `REJ-1` → `422 PAYMENT_REJECTED` (nothing stored, repeatable). "payment service unavailable → 503" is always a 503 and
never books: with the stub up its `SLOW-1` reference outlasts the read timeout (~6.5 s over three attempts); stop
the stub (command in its description) to see the connection-failure path instead. The **credit-card-payment-service** folder calls the stub
directly, without a token (the spec declares no security, ADR-0011); it uses the `credit_card_url` environment
variable.

`notification-service`'s **Notifications** folder needs `reservation:read`, and only shows notifications of the
properties in the token (alice has both; bob only AMS01, carol only RTM01). It reads `reservationId` from the
environment, same as the reservation folder above — run a "Create ..." reservation request first. "List notifications
without reservationId → 400 VALIDATION_FAILED" needs only any valid token. "List notifications without
reservation:read → 403" needs a token that lacks the role: run "Get token (bank-simulator, client credentials)" from
**Auth** first (bank-simulator only has `bank:ingest` and `bank:read`). Notifications are produced asynchronously
(reservation outbox → Debezium → Kafka → notification-service), so a freshly booked reservation may show an empty or
short list until that catches up.

## Demo: bank transfer paid in two parts

The folder **"Demo: bank transfer paid in two parts"** is the payment-matching flow end to end, meant for the Collection
Runner (run the folder, top to bottom). It includes its own token requests, so it switches between alice (books,
reads) and the bank-simulator (pays) by itself:

1. alice books room 202 for 2 nights (240.00, `BANK_TRANSFER`) on a random date in 2030–2039, so the folder can be
   re-run without `409 ROOM_UNAVAILABLE`; saves `reservationId`.
2. The bank pays 120.00 with remittance `1401541457 <reservationId>` → the reservation is still `PENDING_PAYMENT`,
   `amountReceived` 120.00.
3. The bank pays the other 120.00 → `CONFIRMED`, 240.00; `…/payments` lists `MATCHED_PARTIAL` then `MATCHED_FULL`.
4. `notification-service`'s `/notifications` lists this reservation's notifications, oldest first:
   `RESERVATION_CREATED_PENDING_PAYMENT`, `PARTIAL_PAYMENT_RECEIVED`, `RESERVATION_CONFIRMED`.

Payments are applied asynchronously (outbox → Debezium → Kafka → reservation service), so the two reservation checks
and the notifications check retry for up to ~10 s instead of assuming a fixed delay. The **Unmatched payments** folder
lists payments that could not be applied: `GET /unmatched-payments` needs `bank:read` (alice, bob, carol have it) and
shows payments that name no known reservation; `GET /properties/AMS01/unmatched-payments` shows the property's
payments that arrived after a reservation was cancelled or already paid.

## Newman (CLI)

```
npx --yes newman run docs/postman/marvel-hospitality.postman_collection.json \
  -e docs/postman/local.postman_environment.json --folder Auth

npx --yes newman run docs/postman/marvel-hospitality.postman_collection.json \
  -e docs/postman/local.postman_environment.json --folder "Demo: bank transfer paid in two parts"
```

Run against a live `infra` (`make up`) to get real tokens; against the application folders it also needs
`make up-apps`.
