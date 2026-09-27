package com.marvel.hospitality.platform.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@code debezium.slot.lag.bytes{slot}}: how much WAL each logical replication slot of this service's database holds
 * back, i.e. how far Debezium is behind (ADR-0007, ADR-0013). A connector that is down keeps its slot, and Postgres
 * keeps every WAL segment since the slot's last confirmed position until the disk fills (or, in compose,
 * {@code max_slot_wal_keep_size} invalidates the slot); this gauge is what an alert would watch.
 *
 * <p>Polled on a schedule (every 30 seconds by default) rather than on each metrics read, so a scrape or an OTLP
 * push never costs a database round trip. Each poll replaces the set of rows, so a dropped slot disappears from the
 * gauge. Only this database's logical slots are reported ({@code database = current_database()}); a slot that has
 * never confirmed a position is measured from where it started retaining WAL.
 */
public class ReplicationSlotLagMonitor {

    /** Gauge name, tagged with {@code slot} (ADR-0013). */
    public static final String METRIC = "debezium.slot.lag.bytes";

    private static final Logger log = LoggerFactory.getLogger(ReplicationSlotLagMonitor.class);

    private final JdbcClient jdbcClient;
    private final MultiGauge gauge;

    public ReplicationSlotLagMonitor(JdbcClient jdbcClient, MeterRegistry meterRegistry) {
        this.jdbcClient = jdbcClient;
        this.gauge = MultiGauge.builder(METRIC)
                .description("WAL bytes a logical replication slot retains beyond its last confirmed position")
                .baseUnit("bytes")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${marvel.outbox.slot-lag.interval:PT30S}", initialDelay = 0)
    void pollOnSchedule() {
        try {
            poll();
        } catch (DataAccessException e) {
            // The previous values stay; a database outage shows up in the service's health, not as a lag of 0.
            log.warn("Could not read replication slot lag", e);
        }
    }

    /** Reads every slot's lag once and publishes it; returns what it read. */
    public List<SlotLag> poll() {
        List<SlotLag> lags = jdbcClient.sql("""
                        SELECT slot_name,
                               COALESCE(pg_wal_lsn_diff(pg_current_wal_lsn(),
                                                        COALESCE(confirmed_flush_lsn, restart_lsn)), 0)::bigint AS lag
                        FROM pg_replication_slots
                        WHERE database = current_database()
                        """)
                .query((rs, rowNum) -> new SlotLag(rs.getString("slot_name"), rs.getLong("lag")))
                .list();
        gauge.register(lags.stream()
                .map(lag -> MultiGauge.Row.of(Tags.of("slot", lag.slot()), lag.bytes()))
                .toList(), true);
        return lags;
    }

    /** One slot's lag in bytes of WAL. */
    public record SlotLag(String slot, long bytes) {
    }
}
