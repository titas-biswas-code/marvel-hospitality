package com.marvel.hospitality.reservation.infrastructure.scheduling;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code reservation.auto-cancel.*} (ADR-0010). {@code interval} and {@code initialDelay} are read by
 * {@link AutoCancelJob#runOnSchedule}'s {@code @Scheduled} placeholders, not from this record; they are declared here
 * anyway so every property under the prefix is documented in one place.
 *
 * @param enabled whether {@code AutoCancelConfiguration} creates the job
 *     bean at all; {@code false} in the {@code test} profile, so tests that want it running switch it on themselves
 * @param interval pause between the end of one run and the start of the next
 * @param initialDelay delay after startup before the very first run
 * @param batchSize rows read per unlocked page; each row is still claimed and cancelled in its own transaction
 */
@ConfigurationProperties("reservation.auto-cancel")
public record AutoCancelProperties(boolean enabled, Duration interval, Duration initialDelay, int batchSize) {
}
