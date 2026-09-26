# ADR-0012 Security: zero-trust resource servers, Keycloak, no gateway in scope

Status: Accepted · Date: 2026-09-26

## Context
The brief is silent on authentication. Production-ready means every business endpoint is protected and
users are scoped to the properties they may operate.

## Decision
- Keycloak is the IdP (realm `marvel`, export in `infra/keycloak`, imported at start). Contract in
  contracts/security.md.
- **Every service validates the JWT itself** (Spring Security OAuth2 resource server, JWKS cached).
  Validation is a local signature + claims check; cost is negligible. No service trusts an unverified
  header to identify the caller or the property.
- Authorization: realm roles → authorities; property scoping via `properties` claim checked against the
  path variable; `403 FORBIDDEN_PROPERTY` on mismatch. Service accounts carry `properties: ["*"]`.
- No API gateway in this repo. In production an edge gateway would terminate TLS, rate-limit, route and
  *relay the original token* (`TokenRelay`), and services would still validate. Forwarding a plain
  `X-Property-Id` header that services trust is acceptable **only** when the network makes services
  unreachable except through the gateway (NetworkPolicy/mesh with mTLS); we do not assume that.
- Service-to-service calls (future: reservation → payment REST, simulator → payment) use OAuth2
  client-credentials with dedicated service-account clients; the simulator already does.
- The credit-card stub is unauthenticated because its spec is (ADR-0011).
- Secrets: only local-dev defaults, in `infra/.env.example`; README explains that real deployments use
  a secret manager.
- The realm is defined by an idempotent `kcadm.sh` bootstrap script and a committed export generated from it; the
  issuer/JWKS split (`issuer-uri` = host URL, `jwk-set-uri` = compose-internal URL) avoids the classic
  "iss claim mismatch" between tokens minted for the host and services validating inside the Docker network.
- Tests use `spring-security-test` `jwt()` post-processors; one Keycloak Testcontainers smoke test proves
  real token validation.

## Consequences
- The mechanism (filter chain, authority converter, property-access bean, 401/403 problem details, `/whoami`)
  lives once in `platform/security-starter` (ADR-0001); a service adds the dependency and, if needed,
  `marvel.security.additional-public-paths`. Endpoints keep their own `@PreAuthorize` rules.
- Adding a gateway later changes nothing inside services.

## Alternatives considered
- Gateway-only validation + trusted headers: common, but it converts a network misconfiguration into a full
  authz bypass; rejected as the default, documented as an allowed optimisation under network guarantees.
- Opaque tokens with introspection: extra round trip per request; rejected.
- Per-service user tables / basic auth: not production-ready; rejected.
