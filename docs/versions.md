# Resolved versions

Filled in during PR-00 by resolving the latest stable release of each item from Maven Central / Docker Hub.
Every later PR reuses these exact versions. Do not bump without a commit that only bumps versions.

| Item | Version | Resolved on | Notes |
|---|---|---|---|
| Java toolchain | 25 | 2026-09-26 | Fixed project requirement. |
| Gradle wrapper | 9.8.0 | 2026-09-26 | https://services.gradle.org/versions/current |
| foojay-resolver-convention plugin | 1.0.0 | 2026-09-26 | https://plugins.gradle.org/m2/org/gradle/toolchains/foojay-resolver-convention/org.gradle.toolchains.foojay-resolver-convention.gradle.plugin/maven-metadata.xml — downloads JDK 25 when the host JDK is older. |
| io.spring.dependency-management plugin | 1.1.7 | 2026-09-26 | https://plugins.gradle.org/m2/io/spring/dependency-management/io.spring.dependency-management.gradle.plugin/maven-metadata.xml |
| Spring Boot | 4.1.1 | 2026-09-26 | https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/maven-metadata.xml — 4.2.0-M* entries are milestones, excluded. |
| springdoc-openapi | 3.1.1 (`springdoc-openapi-starter-webmvc-ui`) | 2026-09-26 | https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/maven-metadata.xml — 3.x line targets Boot 4. |
| Resilience4j | `io.github.resilience4j:resilience4j-spring-boot4` 2.4.0 | 2026-09-26 | https://repo1.maven.org/maven2/io/github/resilience4j/resilience4j-spring-boot4/maven-metadata.xml — Boot-4-specific starter exists; not used until PR-03. |
| spring-kafka | 4.1.1 | 2026-09-26 | From Boot 4.1.1 BOM: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom |
| Testcontainers | 2.0.5 | 2026-09-26 | From Boot 4.1.1 BOM (same URL as spring-kafka). |
| Flyway (Boot BOM) | 12.4.0 | 2026-09-26 | From Boot 4.1.1 BOM (same URL as spring-kafka). |
| PostgreSQL JDBC driver (Boot BOM) | 42.7.13 | 2026-09-26 | From Boot 4.1.1 BOM (same URL as spring-kafka). |
| Jackson 3 (Boot BOM) | 3.1.5 (`tools.jackson`) | 2026-09-26 | From Boot 4.1.1 BOM (same URL as spring-kafka). |
| WireMock | `org.wiremock:wiremock-jetty12` 3.13.1 | 2026-09-26 | https://repo1.maven.org/maven2/org/wiremock/wiremock-jetty12/maven-metadata.xml — not managed by the Boot BOM. |
| wiremock-spring-boot | `org.wiremock.integrations:wiremock-spring-boot` 4.4.2 | 2026-09-26 | https://repo1.maven.org/maven2/org/wiremock/integrations/wiremock-spring-boot/maven-metadata.xml — Boot 4 support since 4.0.8; provides `@EnableWireMock`. |
| Spring Security (Boot BOM) | 7.1.1 | 2026-09-26 | From Boot 4.1.1 BOM (same URL as spring-kafka). |
| swagger-ui image | swaggerapi/swagger-ui:v5.33.0 | 2026-09-26 | https://hub.docker.com/r/swaggerapi/swagger-ui/tags — unified dev Swagger UI (infra/README.md), not the per-service springdoc UI. |
| Postgres image | postgres:17.11-alpine | 2026-09-26 | https://hub.docker.com/_/postgres — 17 chosen over 18.6 for Debezium maturity. |
| Kafka image | apache/kafka:4.3.1 | 2026-09-26 | https://hub.docker.com/r/apache/kafka/tags — KRaft mode. |
| kafka-ui image | kafbat/kafka-ui:v1.5.0 | 2026-09-26 | https://hub.docker.com/r/kafbat/kafka-ui/tags — provectuslabs/kafka-ui is unmaintained. |
| Debezium connect image | quay.io/debezium/connect:3.6.3.Final | 2026-09-26 | https://quay.io/repository/debezium/connect?tab=tags — Docker Hub `debezium/connect` is frozen at 3.0.0.Final (https://debezium.io/blog/2024/09/18/quay-io-reminder/); bundles the Postgres connector and Outbox Event Router. |
| Keycloak image | quay.io/keycloak/keycloak:26.7.4 | 2026-09-26 | https://quay.io/repository/keycloak/keycloak?tab=tags |
| grafana/otel-lgtm image | grafana/otel-lgtm:0.34.0 | 2026-09-26 | https://hub.docker.com/r/grafana/otel-lgtm/tags |
| eclipse-temurin runtime image | eclipse-temurin:25-jre-noble | 2026-09-26 | Docker Hub. Used as the final stage in each service `Dockerfile`. |
| gradle build image | gradle:9.8.0-jdk25-noble | 2026-09-26 | Docker Hub. Used as the build stage in each service `Dockerfile`; matches the Gradle wrapper version above. |

## Boot 4 artifact names used (vs Boot 3)

Source: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide

- `spring-boot-starter-webmvc` (was `spring-boot-starter-web`; `-web` is now a deprecated alias).
- `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql`.
- `spring-boot-starter-kafka` (new in Boot 4; Boot 3 projects depended on `org.springframework.kafka:spring-kafka` directly).
- `spring-boot-starter-data-jpa`, `spring-boot-starter-actuator`, `spring-boot-starter-validation` — unchanged names.
- `spring-boot-starter-webmvc-test` — Boot 4 adds per-technology test starters (`-webmvc-test`, `-data-jpa-test`, `-kafka-test`, …) next to `spring-boot-starter-test`; each pulls in JUnit 5, AssertJ and Mockito.
- `spring-boot-testcontainers` for `@ServiceConnection` wiring.
- Testcontainers 2.x modules: `org.testcontainers:testcontainers-postgresql` and `org.testcontainers:testcontainers-kafka`, with classes
  `org.testcontainers.postgresql.PostgreSQLContainer` and `org.testcontainers.kafka.KafkaContainer` (package renamed off `org.testcontainers.containers`).
  `@ServiceConnection` is still `org.springframework.boot.testcontainers.service.connection.ServiceConnection`.
- `spring-boot-starter-security-oauth2-resource-server` — used from PR-01, together with the test starters
  `spring-boot-starter-security-oauth2-resource-server-test` and `spring-boot-starter-security-test`.
- `spring-boot-starter-data-jpa-test` (PR-02): `@DataJpaTest` is `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`.
  Unlike Boot 3 it does **not** import Flyway, and its `@AutoConfigureTestDatabase`
  (`org.springframework.boot.jdbc.test.autoconfigure`) defaults to replacing the DataSource; slice tests here add
  `@AutoConfigureTestDatabase(replace = NONE)` + `@ImportAutoConfiguration(FlywayAutoConfiguration.class)`.
- There is no `spring-boot-starter-jdbc-test` in 4.1.1; the outbox starter tests use `spring-boot-starter-test`.
- `@WebMvcTest` pulls auto-configurations from `META-INF/spring/org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc.imports`;
  the platform security and problem starters register themselves there so slice tests get real security and error handling.
- Jackson 3 lives under `tools.jackson.*`, not `com.fasterxml.jackson.*`. Boot 4 exposes `StreamWriteFeature`s as
  `spring.jackson.write.*`, e.g. `spring.jackson.write.write-bigdecimal-as-plain=true` (ADR-0016).
- Platform starters added in PR-02: `com.marvel.hospitality:marvel-problem-spring-boot-starter:1.0.0` and
  `com.marvel.hospitality:marvel-outbox-spring-boot-starter:1.0.0` (built from `platform/`, no external version).
- `@WebMvcTest` lives in package `org.springframework.boot.webmvc.test.autoconfigure` (Boot 4) and does not
  auto-include a user-defined `SecurityFilterChain` configuration class; tests that need real security
  behaviour `@Import` it explicitly.

## Notes

- `platform/` (ADR-0001) is its own Gradle build on the same Spring Boot BOM (4.1.1) and springdoc version as the
  services. A version bump updates `platform/build.gradle` together with every service in the same commit.
  Platform starters are versioned `1.0.0`; services declare that version explicitly.
- Resilience4j and WireMock are **not** managed by the Spring Boot 4.1.1 BOM; their versions above were resolved
  independently against Maven Central and must be pinned explicitly in each `build.gradle`.
- Gotcha: in Boot 4, a `RestClient.Builder` bean is only auto-configured when the `spring-boot-starter-restclient`
  starter is on the classpath. Tests that need a `RestClient` without that starter use `RestClient.create(...)`
  directly.
- All "Resolved on" dates are 2026-09-26 (date this PR's version audit was performed). Re-resolving versions for a
  later PR requires a dedicated version-bump commit — never bump incidentally inside a feature PR.
