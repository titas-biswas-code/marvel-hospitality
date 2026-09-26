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

## Newman (CLI)

```
npx --yes newman run docs/postman/marvel-hospitality.postman_collection.json \
  -e docs/postman/local.postman_environment.json --folder Auth
```

Run against a live `infra` (`make up`) to get real tokens; against the application folders it also needs
`make up-apps`.
