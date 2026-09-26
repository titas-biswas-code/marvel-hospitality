package com.marvel.hospitality.platform.inbox;

import com.marvel.hospitality.platform.outbox.cdc.SharedContainers;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The JVM-wide Postgres from the outbox starter's test fixtures, for every test context in this module.
 * {@code destroyMethod = ""}: a closing Spring context must not stop a container other contexts still use (Ryuk
 * removes it at JVM exit). A database name of its own ({@code "inbox"}, not {@code "outbox"}): {@link
 * SharedContainers#postgres} is a JVM-wide singleton keyed by the first database name it is started with, and this
 * module's tests run in their own Gradle test JVM, separate from the outbox starter's.
 */
@TestConfiguration(proxyBeanMethods = false)
class SharedPostgresConfiguration {

    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return SharedContainers.postgres("inbox");
    }
}
