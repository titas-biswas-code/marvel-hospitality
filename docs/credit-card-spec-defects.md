# Credit-card payment spec: defects found and corrected

The provided `credit-card-payment-service` OpenAPI spec has defects. Generating a client from it as provided does
work, but the payment status comes out as a plain `String` with no allowed values, `lastUpdateDate` as a `String`
rather than a timestamp, and the server URL is unusable. The corrected spec lives with the code
that uses it, in two identical copies: `credit-card-payment-service/src/main/resources/openapi/` (the stub serves it)
and `room-reservation-service/src/main/resources/openapi/` (the client is generated from it); `make check-contracts`
fails if they differ (ADR-0011). The spec exactly as provided is kept in
`docs/contracts/credit-card-payment-api.original.yaml`, so every change can be checked with a diff:

```
diff docs/contracts/credit-card-payment-api.original.yaml \
     credit-card-payment-service/src/main/resources/openapi/credit-card-payment-api.yaml
```
 None of the
corrections changes a request or response on the wire. What was wrong in the original:

1. `servers.url` was `http//:localhost:9090//host/credit-card-payment-api` — malformed scheme, a double slash and
   a stray `host` segment. Corrected to `http://localhost:9090/credit-card-payment-api`.
2. `PaymentStatusResponse.status` declared `format: enum` with a nested list; OpenAPI needs `enum: [CONFIRMED, REJECTED]`.
   As written, generators produce a plain `String` and no allowed values.
3. `PaymentStatusResponse.status` was described as "Expiry date of the driving license" — a copy-paste leftover.
4. `lastUpdateDate` used `format: datetime`; the OpenAPI format is `date-time`, so it was not parsed as a timestamp.
5. No security scheme is declared. Kept as-is (assumed network-internal); a real deployment would use a
   service-account token (ADR-0011, ADR-0012).

One addition that is not a defect fix: an `operationId` (`retrievePaymentStatus`), which only names the generated
client method. Nothing else was tightened; in particular `status` is still not declared `required`, because the
provider does not promise it. The client treats a `200` without a status as a contract violation.
