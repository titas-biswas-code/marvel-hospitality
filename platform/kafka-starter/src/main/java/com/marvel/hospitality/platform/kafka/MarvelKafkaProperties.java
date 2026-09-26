package com.marvel.hospitality.platform.kafka;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param retry the blocking retry policy applied before a record is dead-lettered (ADR-0008)
 */
@ConfigurationProperties("marvel.kafka")
public record MarvelKafkaProperties(@DefaultValue Retry retry) {

    /**
     * Defaults are ADR-0008's: 5 attempts in total, waiting 1s, 2s, 4s, 8s in between (capped at 30s), so a poison
     * message holds up its partition for about 15 seconds before it lands in the DLT. Tests shrink the intervals.
     *
     * @param maxAttempts deliveries in total, the first one included; 1 means no retry
     * @param initialInterval wait before the first retry
     * @param multiplier growth factor of the wait between retries
     * @param maxInterval upper bound of a single wait
     */
    public record Retry(
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("1s") Duration initialInterval,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("30s") Duration maxInterval) {

        public Retry {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("marvel.kafka.retry.max-attempts must be at least 1: " + maxAttempts);
            }
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("marvel.kafka.retry.multiplier must be at least 1.0: " + multiplier);
            }
        }
    }
}
