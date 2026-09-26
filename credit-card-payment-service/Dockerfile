# syntax=docker/dockerfile:1
# Build context: repo root (needs ../docs/contracts/credit-card-payment-api.yaml) — see infra/docker-compose.yml.

# ---- build: compile with the Gradle wrapper on a JDK 25 image ----
FROM gradle:9.8.0-jdk25-noble AS build
COPY docs/contracts /workspace/docs/contracts
WORKDIR /workspace/credit-card-payment-service
COPY credit-card-payment-service/gradlew credit-card-payment-service/settings.gradle credit-card-payment-service/build.gradle ./
COPY credit-card-payment-service/gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY credit-card-payment-service/src src
# Tests run in `./gradlew build` (they need Docker for Testcontainers); the image build only packages.
RUN ./gradlew --no-daemon bootJar \
    && java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination build/extracted

# ---- runtime: JRE only, non-root, layered for cache-friendly rebuilds ----
FROM eclipse-temurin:25-jre-noble
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/credit-card-payment-service/build/extracted/dependencies/ ./
COPY --from=build /workspace/credit-card-payment-service/build/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/credit-card-payment-service/build/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/credit-card-payment-service/build/extracted/application/ ./
USER app
ENV SPRING_PROFILES_ACTIVE=local \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 9090
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
