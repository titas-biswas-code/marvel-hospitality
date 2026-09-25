# syntax=docker/dockerfile:1
# Build context: repo root (needs ../platform) — see infra/docker-compose.yml.

# ---- build: compile with the Gradle wrapper on a JDK 25 image ----
FROM gradle:9.8.0-jdk25-noble AS build
COPY platform /workspace/platform
WORKDIR /workspace/notification-service
COPY notification-service/gradlew notification-service/settings.gradle notification-service/build.gradle ./
COPY notification-service/gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY notification-service/src src
# Tests run in `./gradlew build` (they need Docker for Testcontainers); the image build only packages.
RUN ./gradlew --no-daemon bootJar \
    && java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination build/extracted

# ---- runtime: JRE only, non-root, layered for cache-friendly rebuilds ----
FROM eclipse-temurin:25-jre-noble
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/notification-service/build/extracted/dependencies/ ./
COPY --from=build /workspace/notification-service/build/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/notification-service/build/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/notification-service/build/extracted/application/ ./
USER app
ENV SPRING_PROFILES_ACTIVE=local \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8082
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
