# ADR-0001 Monorepo of independently deployable services, cross-cutting code in platform starters

Status: Accepted · Date: 2026-09-26 · Amended: 2026-09-26 (PR-01, see "Amendment")

## Context
The brief asks for one Spring Boot service (`room-reservation-service`) and names two collaborators
(`credit-card-payment-service` via REST, an event broker topic `bank-transfer-payment-update`). To
demonstrate an event-driven system end to end we also build the producer of that topic and a
notification consumer. Reviewers should be able to clone one repo and run everything.

## Decision
- One Git repository `marvel-hospitality`. Folder names follow the brief verbatim where the brief names
  a service. One `docker-compose.yml` under `infra/` runs all dependencies and all services.
- Each service is a **standalone Gradle project** (own wrapper, `settings.gradle`, `build.gradle`,
  Groovy DSL) with its own Dockerfile. No root build, no parent BOM beyond Spring Boot's own. Each service
  can be built, tested, versioned and deployed alone.
- Cross-cutting code that is identical in every service (JWT security, and later outbox, inbox, Kafka error
  handling, problem-detail advice) lives in **`platform/`**: a separate Gradle build of small Spring Boot
  starters (auto-configuration, `com.marvel.hospitality:marvel-*-spring-boot-starter`). A service opts in
  by declaring the starter dependency with an explicit version.
- Services consume `platform/` **as source** through a Gradle composite build (`includeBuild('../platform')`
  in each service's `settings.gradle`), so a clean clone builds with nothing but Docker and no publishing step.
  In a real organisation the starters would be published to an artifact repository and the `includeBuild` line
  removed; the dependency declarations would not change.
- Starters contain mechanism only (how to validate a token, how to write an outbox row). Business rules,
  domain types and anything one service owns stay in that service. Services never import each other.
- Cross-service agreements live only in `docs/contracts`.

## Consequences
- One implementation and one test suite per cross-cutting concern; services test only that the starter is
  wired in with their settings.
- Each service still builds alone (`cd <service> && ./gradlew build`), but needs the sibling `platform/`
  directory; Docker builds of those services use the repo root as build context.
- Built from source, every service picks up a starter change on its next build: releases are not independent
  for platform code. Accepted locally; publishing versioned starters restores independence and is the
  production path.
- Starters must stay small and backwards compatible; a breaking change is a new major version.
- Contract drift between services is still possible; mitigated by contract docs, a JSON schema check in tests
  (PR-04), and the end-to-end compose smoke test (PR-11).

## Amendment (PR-01)
The original decision duplicated cross-cutting classes per service (marked `// platform-candidate`) and named
a versioned platform library as the next step "once a third consumer appears". PR-01 produced that third
consumer immediately: the security code was ~10 identical classes in three services, and the outbox, inbox and
Kafka error handling of later PRs would repeat the pattern. Duplication made each copy harder to review and
to keep identical, so the library was introduced now, built from source to keep the one-clone experience.

## Alternatives considered
- Duplicate per service (the original decision): no coupling at all, but N copies to review and keep in sync;
  superseded as described above.
- Versioned library published to GitHub Packages / Maven local: truest independence, but a reviewer's clean
  clone would need a publish step or registry credentials first; kept as the production path.
- Gradle multi-project with a shared `common` module and one root build: couples every service into one build
  and invites a grab-bag module; rejected.
- Polyrepo: correct for a real organisation, hostile to a reviewer with one link; rejected.
