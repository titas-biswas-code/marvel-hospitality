package com.marvel.hospitality.payment;

import com.marvel.hospitality.platform.outbox.cdc.SharedContainers;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every test context in this service gets the same JVM-wide Postgres (platform/outbox-starter test fixtures): one
 * container per test run instead of one per Spring context. It runs with {@code wal_level=logical} like compose, so
 * the CDC test can use it too. {@code destroyMethod = ""}: a closing context must not stop a container the other
 * cached contexts still use (Ryuk removes it at JVM exit). Tests isolate by unique ids, never by truncating tables.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return SharedContainers.postgres("payment");
    }
}
