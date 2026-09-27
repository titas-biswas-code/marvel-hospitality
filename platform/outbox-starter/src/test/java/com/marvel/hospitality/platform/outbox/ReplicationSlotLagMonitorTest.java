package com.marvel.hospitality.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.platform.outbox.cdc.SharedContainers;
import com.marvel.hospitality.platform.outbox.testapp.TestApplication;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The slot-lag gauge every CDC'd service gets from this starter (ADR-0007, ADR-0013), tested once here against the
 * shared {@code wal_level=logical} Postgres. Same context as {@link OutboxEventWriterTest}. Each test creates its own
 * slot and always drops it: an unconsumed slot retains WAL, and the server allows only a few.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.application.name=outbox-test-app",
        "spring.sql.init.mode=always",
        "management.tracing.sampling.probability=1.0"})
@Import(SharedPostgresConfiguration.class)
class ReplicationSlotLagMonitorTest {

    @Autowired
    ReplicationSlotLagMonitor monitor;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void slotLagGaugeIsRegistered() {
        String slot = newSlotName();
        createSlot(slot);
        try {
            monitor.poll();
            double before = lagOf(slot);
            assertThat(before).isGreaterThanOrEqualTo(0);

            writeSomeWal();
            monitor.poll();

            assertThat(lagOf(slot)).isGreaterThan(before);
        } finally {
            dropSlot(slot);
        }
    }

    @Test
    void dropsGaugeForRemovedSlot() {
        String slot = newSlotName();
        createSlot(slot);
        try {
            monitor.poll();
            assertThat(meterRegistry.find(ReplicationSlotLagMonitor.METRIC).tag("slot", slot).gauge()).isNotNull();
        } finally {
            dropSlot(slot);
        }

        monitor.poll();

        assertThat(meterRegistry.find(ReplicationSlotLagMonitor.METRIC).tag("slot", slot).gauge()).isNull();
    }

    @Test
    void slotLagIsReadableByNonSuperuserReplicationRole() {
        // Compose's service roles own their database and have REPLICATION, but are not superusers
        // (infra/postgres/init/01-databases.sh); the query must work with exactly those rights.
        jdbcClient.sql("""
                DO $$ BEGIN
                  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'lag_reader') THEN
                    CREATE ROLE lag_reader LOGIN REPLICATION NOSUPERUSER PASSWORD 'lag_reader';
                  END IF;
                END $$
                """).update();
        String slot = newSlotName();
        createSlot(slot);
        try {
            PostgreSQLContainer postgres = SharedContainers.postgres("outbox");
            JdbcClient asServiceRole = JdbcClient.create(
                    new DriverManagerDataSource(postgres.getJdbcUrl(), "lag_reader", "lag_reader"));

            var lags = new ReplicationSlotLagMonitor(asServiceRole, new SimpleMeterRegistry()).poll();

            assertThat(lags).extracting(ReplicationSlotLagMonitor.SlotLag::slot).contains(slot);
        } finally {
            dropSlot(slot);
        }
    }

    /** Looked up again on every read: each poll re-registers the rows, replacing the previous gauges. */
    private double lagOf(String slot) {
        Gauge gauge = meterRegistry.find(ReplicationSlotLagMonitor.METRIC).tag("slot", slot).gauge();
        assertThat(gauge).as("gauge for slot " + slot).isNotNull();
        return gauge.value();
    }

    private static String newSlotName() {
        return "lag_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private void createSlot(String slot) {
        jdbcClient.sql("SELECT pg_create_logical_replication_slot(:slot, 'pgoutput')").param("slot", slot).query().listOfRows();
    }

    private void dropSlot(String slot) {
        jdbcClient.sql("SELECT pg_drop_replication_slot(:slot)").param("slot", slot).query().listOfRows();
    }

    private void writeSomeWal() {
        jdbcClient.sql("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, topic, producer, payload)
                SELECT gen_random_uuid(), 'reservation', 'lag-' || g, 'ReservationStatusChanged',
                       'reservation-status-changed', 'outbox-test-app', '{}'::jsonb
                FROM generate_series(1, 50) g
                """).update();
    }
}
