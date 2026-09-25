# ADR-0002 Property as a first-class concept; property in path, entitlement in JWT

Status: Accepted · Date: 2026-09-26

## Context
The brief mentions "payments received on hotels account" (plural hotels) and room numbers, which are
only unique within a hotel. Marvel is one corporation operating several properties; this is **not**
a multi-tenant SaaS. Staff such as regional managers or a central booking desk legitimately act on
more than one property.

## Decision
- `Property` is a persisted aggregate (`property` table: id, name, timezone, bank account). Rooms,
  rates and reservations belong to a property. Every property-scoped row has `property_id`.
- `propertyId` is a **path segment** of every property-scoped REST resource:
  `/properties/{propertyId}/reservations`. It identifies the resource being acted on.
- The JWT carries a `properties` claim listing the properties the caller may act on. Authorization is
  `path.propertyId ∈ token.properties` (or `*`). This is the *entitlement*, not the target.
- Every event our services produce carries `propertyId` in value and header. The brief-defined bank
  topic cannot (the bank does not know Marvel's org chart), so `reservationId` must be globally unique
  across properties (see contracts/identifiers.md).
- We deliberately do not use the word *tenant* anywhere in code.

## Consequences
- Clear REST semantics; a receptionist's UI simply pins the only value in their claim.
- Reference data (rooms, rates) is per property; seeding grows with properties.
- Row-Level Security keyed on `property_id` is a natural future hardening (ADR-0003, rejected for now).

## Alternatives considered
- Property from a custom JWT claim only, forwarded by a gateway in a header: right for single-tenant
  SaaS users, wrong here because a user with N properties cannot express "this request is for
  property X" through a claim. Also see ADR-0012 on trusting forwarded headers.
- Global room ids (UUID per room, no property): loses the natural key the brief uses ("Room Number")
  and hides the multi-property nature of the business; rejected.
