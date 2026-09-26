package com.marvel.hospitality.creditcard.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only source of "now" in this service: {@code lastUpdateDate} in the response is read from this clock,
 * never {@code Instant.now()} directly (the same time convention as every other service, even though this stub
 * has no domain). Tests replace it with a fixed clock instead of asserting against wall-clock time.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
