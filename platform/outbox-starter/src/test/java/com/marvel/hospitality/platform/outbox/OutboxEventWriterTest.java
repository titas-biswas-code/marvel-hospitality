package com.marvel.hospitality.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marvel.hospitality.platform.outbox.testapp.OutboxTestService;
import com.marvel.hospitality.platform.outbox.testapp.TestApplication;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The outbox-writing behaviour every service gets from this starter, tested once here through a minimal Boot app
 * that picks the starter up via its AutoConfiguration.imports. Services only need a wiring test.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.application.name=outbox-test-app",
        "spring.sql.init.mode=always"})
@Testcontainers
class OutboxEventWriterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");

    @Autowired
    OutboxEventWriter writer;

    @Autowired
    OutboxTestService service;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void writesRowWithRoutingColumnsAndJsonPayload() {
        TestPayload payload = new TestPayload("P4145478", new BigDecimal("240.00"));
        OutboxMessage message = new OutboxMessage("reservation", "P4145478-row", "ReservationStatusChanged", 1,
                "reservation-status-changed", "AMS01", payload);

        UUID id = service.appendAndCommit(message);

        Map<String, Object> row = jdbcClient.sql("SELECT * FROM outbox_event WHERE id = :id")
                .param("id", id)
                .query()
                .singleRow();
        assertThat(row.get("id")).isEqualTo(id);
        assertThat(row.get("aggregate_type")).isEqualTo("reservation");
        assertThat(row.get("aggregate_id")).isEqualTo("P4145478-row");
        assertThat(row.get("event_type")).isEqualTo("ReservationStatusChanged");
        assertThat(row.get("event_version")).isEqualTo(1);
        assertThat(row.get("topic")).isEqualTo("reservation-status-changed");
        assertThat(row.get("property_id")).isEqualTo("AMS01");
        assertThat(row.get("producer")).isEqualTo("outbox-test-app");
        assertThat(row.get("traceparent")).isNull();

        OffsetDateTime createdAt = jdbcClient.sql("SELECT created_at FROM outbox_event WHERE id = :id")
                .param("id", id)
                .query(OffsetDateTime.class)
                .single();
        assertThat(createdAt).isEqualTo(OffsetDateTime.parse("2026-09-26T10:15:30Z"));

        String totalAmount = jdbcClient.sql("SELECT payload ->> 'totalAmount' FROM outbox_event WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(totalAmount).isEqualTo("240.00");
        String reservationId = jdbcClient.sql("SELECT payload ->> 'reservationId' FROM outbox_event WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(reservationId).isEqualTo("P4145478");
    }

    @Test
    void joinsCallerTransactionAndRollsBackWithIt() {
        OutboxMessage message = new OutboxMessage("reservation", "P4145478-rollback", "ReservationStatusChanged",
                "reservation-status-changed", "AMS01", new TestPayload("P4145478", BigDecimal.TEN));

        assertThatThrownBy(() -> service.appendThenFailTheRestOfTheTransaction(message))
                .isInstanceOf(IllegalStateException.class);

        Long rows = jdbcClient.sql("SELECT count(*) FROM outbox_event WHERE aggregate_id = :aggregateId")
                .param("aggregateId", "P4145478-rollback")
                .query(Long.class)
                .single();
        assertThat(rows).isZero();
    }

    @Test
    void refusesToWriteOutsideATransaction() {
        OutboxMessage message = new OutboxMessage("reservation", "P4145478-no-tx", "ReservationStatusChanged",
                "reservation-status-changed", "AMS01", new TestPayload("P4145478", BigDecimal.ONE));

        assertThatThrownBy(() -> writer.append(message)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void producerDefaultsToApplicationName() {
        OutboxMessage message = new OutboxMessage("reservation", "P4145478-producer", "ReservationStatusChanged",
                "reservation-status-changed", "AMS01", new TestPayload("P4145478", BigDecimal.ONE));

        UUID id = service.appendAndCommit(message);

        String producer = jdbcClient.sql("SELECT producer FROM outbox_event WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(producer).isEqualTo("outbox-test-app");
    }

    record TestPayload(String reservationId, BigDecimal totalAmount) {
    }
}
