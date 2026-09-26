package com.marvel.hospitality.reservation.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only source of "now" in this service (ADR-0010: tests move time instead of sleeping). Always UTC; property-local dates are derived from each
 * property's {@code timezone}. Tests replace it with a fixed or movable clock ({@code @Primary}) instead of sleeping.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
