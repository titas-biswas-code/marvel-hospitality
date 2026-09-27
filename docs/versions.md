# Resolved versions

Each item is the latest stable release from Maven Central / Docker Hub (or GitHub for Actions) on the date shown.
All services use these exact versions. Do not bump without a commit that only bumps versions.

| Item | Version | Resolved on | Notes |
|---|---|---|---|
| Java toolchain | 25 | 2026-09-26 | Fixed project requirement. |
| Gradle wrapper | 9.8.0 | 2026-09-26 | https://services.gradle.org/versions/current |
| foojay-resolver-convention plugin | 1.0.0 | 2026-09-26 | https://plugins.gradle.org/m2/org/gradle/toolchains/foojay-resolver-convention/org.gradle.toolchains.foojay-resolver-convention.gradle.plugin/maven-metadata.xml — downloads JDK 25 when the host JDK is older. |
| io.spring.dependency-management plugin | 1.1.7 | 2026-09-26 | https://plugins.gradle.org/m2/io/spring/dependency-management/io.spring.dependency-management.gradle.plugin/maven-metadata.xml |
| Spring Boot | 4.1.1 | 2026-09-26 | https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/maven-metadata.xml — 4.2.0-M* entries are milestones, excluded. |
| springdoc-openapi | 3.1.1 (`springdoc-openapi-starter-webmvc-ui`) | 2026-09-26 | https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/maven-metadata.xml — 3.x line targets Boot 4. |
| Resilience4j | `io.github.resilience4j:resilience4j-spring-boot4` 2.4.0 | 2026-09-26 | https://repo1.maven.org/maven2/io/github/resilience4j/resilience4j-spring-boot4/maven-metadata.xml — Boot-4-specific starter; used by room-reservation-service. |
| spring-kafka | 4.1.1 | 2026-09-26 | From Boot 4.1.1 BOM: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom |
| Testcontainers | 2.0.5 | 2026-09-26 | From Boot 4.1.1 BOM (same URL as spring-kafka). |
| Flyway (Boot BOM) | 12.4.0 | 2026-09-26 | From Boot 4.1.1 BOM (same URL as spring-kafka). |
| PostgreSQL JDBC driver (Boot BOM) | 42.7.13 | 2026-09-26 | From Boot 4.1.1 BOM (same URL as spring-kafka). |
| Jackson 3 (Boot BOM) | 3.1.5 (`tools.jackson`) | 2026-09-26 | From Boot 4.1.1 BOM (same URL as spring-kafka). |
| WireMock | `org.wiremock:wiremock-jetty12` 3.13.1 | 2026-09-26 | https://repo1.maven.org/maven2/org/wiremock/wiremock-jetty12/maven-metadata.xml — not managed by the Boot BOM. |
| wiremock-spring-boot | `org.wiremock.integrations:wiremock-spring-boot` 4.4.2 | 2026-09-26 | https://repo1.maven.org/maven2/org/wiremock/integrations/wiremock-spring-boot/maven-metadata.xml — Boot 4 support since 4.0.8; provides `@EnableWireMock`. |
| openapi-generator Gradle plugin | `org.openapi.generator` 7.25.0 | 2026-09-26 | https://plugins.gradle.org/m2/org/openapi/generator/org.openapi.generator.gradle.plugin/maven-metadata.xml — `java` generator, `restclient` library with `useSpringBoot4`, `useJackson3`, `useJspecify`. |
| Spring Security (Boot BOM) | 7.1.1 | 2026-09-26 | From Boot 4.1.1 BOM (same URL as spring-kafka). |
| Awaitility (Boot BOM) | 4.3.0 | 2026-09-26 | From Boot 4.1.1 BOM (same URL as spring-kafka). Test-only: waits for asynchronous Kafka effects without `Thread.sleep`. |
| Micrometer (Boot BOM) | 1.17.1 | 2026-09-26 | From Boot 4.1.1 BOM (same URL as spring-kafka). `micrometer-core` is an API dependency of `platform/kafka-starter` (`kafka.dlt.messages`). |
| Micrometer Tracing (Boot BOM) | 1.7.1 | 2026-09-27 | From Boot 4.1.1 BOM (`micrometer-tracing.version`). OTel bridge `micrometer-tracing-bridge-otel`, via `spring-boot-starter-opentelemetry`. |
| OpenTelemetry Java (Boot BOM) | 1.62.0 | 2026-09-27 | From Boot 4.1.1 BOM (`opentelemetry.version`): API, SDK, OTLP exporter. |
| OpenTelemetry Logback appender | `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0` 2.28.0-alpha | 2026-09-27 | https://repo1.maven.org/maven2/io/opentelemetry/instrumentation/opentelemetry-logback-appender-1.0/maven-metadata.xml — **not** managed by the Boot BOM; the release whose POM depends on exactly `opentelemetry-api` 1.62.0 (later releases need a newer API). Pinned in `platform/observability-starter/build.gradle`. |
| swagger-ui image | swaggerapi/swagger-ui:v5.33.0 | 2026-09-26 | https://hub.docker.com/r/swaggerapi/swagger-ui/tags — unified dev Swagger UI (infra/README.md), not the per-service springdoc UI. |
| Postgres image | postgres:17.11-alpine | 2026-09-26 | https://hub.docker.com/_/postgres — 17 chosen over 18.6 for Debezium maturity. |
| Kafka image | apache/kafka:4.3.1 | 2026-09-26 | https://hub.docker.com/r/apache/kafka/tags — KRaft mode. |
| kafka-ui image | kafbat/kafka-ui:v1.5.0 | 2026-09-26 | https://hub.docker.com/r/kafbat/kafka-ui/tags — provectuslabs/kafka-ui is unmaintained. |
| Debezium connect image | quay.io/debezium/connect:3.6.3.Final | 2026-09-26 | https://quay.io/repository/debezium/connect?tab=tags — Docker Hub `debezium/connect` is frozen at 3.0.0.Final (https://debezium.io/blog/2024/09/18/quay-io-reminder/); bundles the Postgres connector and Outbox Event Router. |
| Keycloak image | quay.io/keycloak/keycloak:26.7.4 | 2026-09-26 | https://quay.io/repository/keycloak/keycloak?tab=tags |
| grafana/otel-lgtm image | grafana/otel-lgtm:0.34.0 | 2026-09-26 | https://hub.docker.com/r/grafana/otel-lgtm/tags |
| eclipse-temurin runtime image | eclipse-temurin:25-jre-noble | 2026-09-26 | Docker Hub. Used as the final stage in each service `Dockerfile`. |
| gradle build image | gradle:9.8.0-jdk25-noble | 2026-09-26 | Docker Hub. Used as the build stage in each service `Dockerfile`; matches the Gradle wrapper version above. |
| tools image base | docker:29.8.1-cli (Alpine 3.24) | 2026-09-28 | Docker Hub. `infra/tools/Dockerfile`; bash, curl, jq, make, python3 and socat come from Alpine's package index at build time (not pinned). |
| GitHub Actions (CI) | `actions/checkout@v7` (7.0.1), `actions/setup-java@v6` (6.0.1), `gradle/actions/setup-gradle@v6` (6.3.0), `actions/upload-artifact@v7` (7.0.1) | 2026-09-27 | Latest releases on GitHub; pinned to the major version in `.github/workflows/ci.yml`. Runner `ubuntu-24.04`. `setup-gradle` uses `cache-provider: basic` (MIT) rather than the default commercial caching library. |

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
- `spring-boot-starter-security-oauth2-resource-server` — used with the test starters
  `spring-boot-starter-security-oauth2-resource-server-test` and `spring-boot-starter-security-test`.
- `spring-boot-starter-data-jpa-test`: `@DataJpaTest` is `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`.
  Unlike Boot 3 it does **not** import Flyway, and its `@AutoConfigureTestDatabase`
  (`org.springframework.boot.jdbc.test.autoconfigure`) defaults to replacing the DataSource; slice tests here add
  `@AutoConfigureTestDatabase(replace = NONE)` + `@ImportAutoConfiguration(FlywayAutoConfiguration.class)`.
- There is no `spring-boot-starter-jdbc-test` in 4.1.1; the outbox starter tests use `spring-boot-starter-test`.
- `@WebMvcTest` pulls auto-configurations from `META-INF/spring/org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc.imports`;
  the platform security and problem starters register themselves there so slice tests get real security and error handling.
- Jackson 3 lives under `tools.jackson.*`, not `com.fasterxml.jackson.*`. Boot 4 exposes `StreamWriteFeature`s as
  `spring.jackson.write.*`, e.g. `spring.jackson.write.write-bigdecimal-as-plain=true` (ADR-0016).
- `spring-boot-starter-restclient`: Boot 4 split `RestClient.Builder` auto-configuration out of the web starter.
- `spring-boot-starter-aspectj`: Boot 4's name for the former `spring-boot-starter-aop`; Resilience4j's
  `@Retry`/`@CircuitBreaker` annotations need it. `resilience4j-spring-boot4` itself does not pull it in.
- `spring-boot-starter-webmvc-test` + `org.wiremock.integrations:wiremock-spring-boot` (`@EnableWireMock`, package
  `org.wiremock.spring`) for the credit-card client tests.
- Platform starters: `com.marvel.hospitality:marvel-problem-spring-boot-starter:1.0.0` and
  `com.marvel.hospitality:marvel-outbox-spring-boot-starter:1.0.0` (built from `platform/`, no external version).
- `spring-boot-starter-opentelemetry`: Boot 4's observability starter — Micrometer Tracing with the OTel
  bridge, `micrometer-registry-otlp`, the OTel SDK and OTLP exporter. Boot 4 builds an OTLP log exporter but does not
  bridge Logback to it; `platform/observability-starter` adds the OTel Logback appender. Property keys differ from
  Boot 3 and the old ones fail at startup (deprecated at level `error`): `management.opentelemetry.tracing.export.otlp.endpoint`
  (was `management.otlp.tracing.endpoint`), `management.opentelemetry.logging.export.otlp.endpoint` (was
  `management.otlp.logging.endpoint`); metrics stay `management.otlp.metrics.export.url`. `management.tracing.export.enabled=false`
  (the replacement of `management.tracing.enabled`) turns the tracer into a no-op; to stop only the OTLP export use
  `management.tracing.export.otlp.enabled=false`.
- Health contributor API moved to `org.springframework.boot.health.contributor` (`AbstractHealthIndicator`, `Health`,
  `Status`) in the `spring-boot-health` module; Boot 4.1.1 has no Kafka health indicator of its own.
- `@WebMvcTest` lives in package `org.springframework.boot.webmvc.test.autoconfigure` (Boot 4) and does not
  auto-include a user-defined `SecurityFilterChain` configuration class; tests that need real security
  behaviour `@Import` it explicitly.

## Notes

- `platform/` (ADR-0001) is its own Gradle build on the same Spring Boot BOM (4.1.1) and springdoc version as the
  services. A version bump updates `platform/build.gradle` together with every service in the same commit.
  Platform starters are versioned `1.0.0`; services declare that version explicitly.
- openapi-generator, Resilience4j and WireMock are **not** managed by the Spring Boot 4.1.1 BOM; their versions above were resolved
  independently against Maven Central and must be pinned explicitly in each `build.gradle`.
- Gotcha: in Boot 4, a `RestClient.Builder` bean is only auto-configured when the `spring-boot-starter-restclient`
  starter is on the classpath. Tests that need a `RestClient` without that starter use `RestClient.create(...)`
  directly.
- The "Resolved on" column is the date each version was checked. Re-resolving versions needs a dedicated
  version-bump commit — never bump incidentally inside a feature change.
- spring-kafka 4 Jackson 3 names: `JacksonJsonMessageConverter` / `JacksonJsonDeserializer` / `JacksonJsonSerializer`
  (package `org.springframework.kafka.support.{converter,serializer}`) replace the Jackson 2 `Json*` classes. Boot's
  `kafkaListenerContainerFactory` picks up `CommonErrorHandler`, `RecordMessageConverter` and `ContainerCustomizer`
  beans, and `DefaultKafkaConsumerFactoryCustomizer` (`org.springframework.boot.kafka.autoconfigure`) customises its
  consumer factory. `DeadLetterPublishingRecoverer` defaults to `<topic>-dlt` in spring-kafka 4; this repo sets
  `<topic>.DLT` explicitly.
