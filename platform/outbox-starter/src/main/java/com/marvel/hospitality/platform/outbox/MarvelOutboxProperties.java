package com.marvel.hospitality.platform.outbox;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param producer the {@code outbox_event.producer} value this service writes; when unset,
 *        {@link MarvelOutboxAutoConfiguration} falls back to {@code spring.application.name}
 * @param purge the scheduled clean-up of old rows (docs/contracts/outbox-and-inbox.md)
 * @param slotLag the {@code debezium.slot.lag.bytes} gauge (ADR-0007, ADR-0013)
 */
@ConfigurationProperties("marvel.outbox")
public record MarvelOutboxProperties(@Nullable String producer, @DefaultValue Purge purge, @DefaultValue SlotLag slotLag) {

    /**
     * @param enabled whether {@link OutboxPurgeJob} is registered (and scheduling switched on for it)
     * @param retention rows whose {@code created_at} is older than this are deleted
     * @param cron when the job runs, in UTC; read by {@code @Scheduled} from {@code marvel.outbox.purge.cron} and
     *        declared here so it shows up in the configuration metadata
     */
    public record Purge(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("7d") Duration retention,
            @DefaultValue(OutboxPurgeJob.DEFAULT_CRON) String cron) {
    }

    /**
     * @param enabled whether {@link ReplicationSlotLagMonitor} is registered (only when a {@code MeterRegistry} exists)
     * @param interval how often the slots are read; read by {@code @Scheduled} from
     *        {@code marvel.outbox.slot-lag.interval} and declared here so it shows up in the configuration metadata
     */
    public record SlotLag(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT30S") Duration interval) {
    }
}
