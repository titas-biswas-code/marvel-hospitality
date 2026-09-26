package com.marvel.hospitality.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.reservation.MockJwtDecoderConfiguration;
import com.marvel.hospitality.reservation.TestcontainersConfiguration;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transactional outbox against real Postgres (ADR-0006): the reservation row and its event row commit together
 * or not at all. Uses the same context as the other {@code @SpringBootTest}s (and so the same container); rooms and
 * dates are chosen so no other test touches them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class ReservationOutboxTransactionTest {

    @Autowired
    CreateReservationUseCase createReservation;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcClient jdbc;

    @Test
    void writesOutboxRowInSameTransactionAsReservation() {
        ReservationView created = createReservation.create(new CreateReservationCommand("AMS01", "Ada Lovelace", "401",
                LocalDate.parse("2030-05-01"), LocalDate.parse("2030-05-03"), RoomSegment.EXTRA_LARGE,
                PaymentMode.CASH, null));
        String reservationId = created.reservation().reservationId().value();

        assertThat(countReservations(reservationId)).isEqualTo(1);
        Map<String, Object> outbox = jdbc.sql("""
                        SELECT aggregate_type, event_type, event_version, topic, property_id, producer,
                               payload->>'reservationId' AS payload_reservation_id,
                               payload->>'status' AS payload_status,
                               jsonb_typeof(payload->'previousStatus') AS previous_status_type,
                               payload->>'totalAmount' AS payload_total
                          FROM outbox_event WHERE aggregate_id = :id
                        """)
                .param("id", reservationId)
                .query().singleRow();
        assertThat(outbox)
                .containsEntry("aggregate_type", "reservation")
                .containsEntry("event_type", "ReservationStatusChanged")
                .containsEntry("event_version", 1)
                .containsEntry("topic", "reservation-status-changed")
                .containsEntry("property_id", "AMS01")
                .containsEntry("producer", "room-reservation-service")
                .containsEntry("payload_reservation_id", reservationId)
                .containsEntry("payload_status", "CONFIRMED")
                .containsEntry("previous_status_type", "null")
                .containsEntry("payload_total", "520.00");

        String rolledBackId = transactions.execute(status -> {
            ReservationView rolledBack = createReservation.create(new CreateReservationCommand("RTM01", "Ada Lovelace",
                    "401", LocalDate.parse("2030-05-01"), LocalDate.parse("2030-05-03"), RoomSegment.EXTRA_LARGE,
                    PaymentMode.CASH, null));
            // Joins this outer transaction (REQUIRED); forcing it to roll back must take both rows with it.
            status.setRollbackOnly();
            return rolledBack.reservation().reservationId().value();
        });

        assertThat(countReservations(rolledBackId)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE aggregate_id = :id")
                .param("id", rolledBackId).query(Integer.class).single()).isZero();
    }

    private int countReservations(String reservationId) {
        return jdbc.sql("SELECT count(*) FROM reservation WHERE reservation_id = :id")
                .param("id", reservationId).query(Integer.class).single();
    }
}
