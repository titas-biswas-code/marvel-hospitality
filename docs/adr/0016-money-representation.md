# ADR-0016 Money as a small domain value object; JavaMoney (JSR 354 / Moneta) deferred

Status: Accepted · Date: 2026-09-26

## Context
Reservations carry a total, an amount received and (later) refund amounts. The assignment is single-currency
(`EUR`), amounts are compared exactly at scale 2 with no tolerance (ADR-0009), and every wire format is flat:
REST and events expose `totalAmount` / `amountReceived` / `amount` as JSON numbers next to one `currency`
field (contracts/rest-api.md, contracts/events.md). The database stores `numeric(12,2)` + `currency char(3)`.

## Decision
- A domain-internal `Money(BigDecimal amount, String currency)` record in the reservation `domain` package.
  It normalises to scale 2 with `RoundingMode.UNNECESSARY` (more fraction digits is a bug, never silently
  rounded), rejects negative amounts and any currency other than `EUR`, and offers only what the domain uses
  (`plus`, `times(nights)`, comparisons).
- `Money` never crosses a boundary: API DTOs, outbox payloads and JPA entities map it to the contracts' flat
  `BigDecimal` + `currency` fields. No REST or event contract changes because of it.
- JSON writes `BigDecimal` in plain notation (`spring.jackson.write.write-bigdecimal-as-plain=true`), so a
  scale-2 amount always renders as `240.00`, never `2.4E+2`.

## Consequences
- The rules "two decimals, EUR, non-negative" live in one place instead of at every `BigDecimal` use site.
- Supporting a second currency means widening `Money`'s currency rule, adding FX/rounding policy and a
  per-property or per-rate currency; that is the point at which the alternative below becomes worth it.

## Alternatives considered
- **JavaMoney (JSR 354) with the Moneta reference implementation** (`MonetaryAmount`, `FastMoney`/`Money`):
  the right tool for multi-currency, exchange rates and currency-specific rounding, and it can be persisted with
  Hibernate through a composite/custom type mapping (e.g. Hypersistence Utils' `MonetaryAmountType`). Deferred
  because for one currency and exact scale-2 arithmetic it adds a dependency, a persistence mapping and
  serializers to flatten its default `{amount, currency}` JSON object back into our contract fields, while not
  removing the need for plain-notation `BigDecimal` output. **Future**: adopt when multi-currency arrives.
- Bare `BigDecimal` everywhere: no extra type, but the scale/currency rules would be repeated (and forgotten)
  at every call site; rejected.
