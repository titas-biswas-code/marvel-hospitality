# ADR-0001 Monorepo of independently deployable services, no shared code

Status: Accepted · Date: 2026-09-26

## Context
The brief asks for one Spring Boot service (`room-reservation-service`) and names two collaborators
(`credit-card-payment-service` via REST, an event broker topic `bank-transfer-payment-update`). To
demonstrate an event-driven system end to end we also build the producer of that topic and a
notification consumer. Reviewers should be able to clone one repo and run everything.

## Decision
- One Git repository `marvel-hospitality`. Folder names follow the brief verbatim where the brief names
  a service. One `docker-compose.yml` under `infra/` runs all dependencies and all services.
- Each service is a **standalone Gradle project** (own wrapper, `settings.gradle`, `build.gradle`,
  Groovy DSL) with its own Dockerfile. No root build, no shared library, no parent BOM beyond Spring
  Boot's own. Each service can be built, tested, versioned and deployed alone.
- Cross-cutting code that would normally live in a platform library (outbox entity, inbox repository,
  problem-detail advice, JWT authority converter) is **duplicated** per service, kept small, and
  marked with a `// platform-candidate` comment.
- Cross-service agreements live only in `docs/contracts`. Services never import each other.

## Consequences
- Independent deployability is real, not nominal: no lock-step releases, no shared-lib version skew.
- Duplication of ~5 small classes per service; acceptable at this scale, and the honest alternative
  (a versioned `marvel-platform` library) is documented as the next step once a third consumer appears.
- Contract drift is possible; mitigated by contract docs, a JSON schema check in tests (PR-04), and
  the end-to-end compose smoke test (PR-11).

## Alternatives considered
- Gradle multi-project with shared `common` module: simplest for a take-home, but it is exactly the
  coupling the "independently deployable" requirement rules out; rejected.
- Polyrepo: correct for a real organisation, hostile to a reviewer with one link; rejected.
