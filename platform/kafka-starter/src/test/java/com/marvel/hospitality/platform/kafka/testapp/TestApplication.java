package com.marvel.hospitality.platform.kafka.testapp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Minimal app that picks the starter up through its AutoConfiguration.imports, as a service would. No database: the
 * shared-container fixtures bring JDBC onto the test classpath, but nothing here needs a {@code DataSource}.
 */
@SpringBootApplication(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
public class TestApplication {

    /** Services get one from actuator; this module has no actuator, so the DLT counter needs one here. */
    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
