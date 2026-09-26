package com.marvel.hospitality.platform.outbox;

import com.marvel.hospitality.platform.outbox.cdc.SharedContainers;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The JVM-wide Postgres from the test fixtures, for every test context in this module. {@code destroyMethod = ""}:
 * a closing Spring context must not stop a container other contexts still use (Ryuk removes it at JVM exit).
 */
@TestConfiguration(proxyBeanMethods = false)
class SharedPostgresConfiguration {

    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return SharedContainers.postgres("outbox");
    }
}
