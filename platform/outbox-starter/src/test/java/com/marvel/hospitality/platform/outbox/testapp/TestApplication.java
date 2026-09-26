package com.marvel.hospitality.platform.outbox.testapp;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Minimal service for the starter's tests; the starter is picked up through its AutoConfiguration.imports exactly
 * as in a real service. In a sub-package so component scanning cannot register the starter's beans a second time.
 */
@SpringBootApplication
public class TestApplication {

    /** Fixed so {@code writesRowWithRoutingColumnsAndJsonPayload} can assert an exact {@code created_at}. */
    @Bean
    Clock clock() {
        return Clock.fixed(Instant.parse("2026-09-26T10:15:30Z"), ZoneOffset.UTC);
    }
}
