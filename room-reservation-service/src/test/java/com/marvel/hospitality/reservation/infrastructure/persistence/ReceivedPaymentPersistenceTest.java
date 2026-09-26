package com.marvel.hospitality.reservation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.reservation.TestcontainersConfiguration;
import com.marvel.hospitality.reservation.application.ReceivedPaymentRepository;
import com.marvel.hospitality.reservation.application.ReservationRepository;
import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.PaymentMatchOutcome;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationId;
import com.marvel.hospitality.reservation.domain.ReservationState;
import com.marvel.hospitality.reservation.domain.ReservationStatus;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import com.marvel.hospitality.reservation.domain.StayPeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * {@link JpaReceivedPaymentRepositoryAdapter} against a real Postgres, sibling to {@link ReservationPersistenceTest}
 * (identical slice annotations, so both share one cached Spring context and one Testcontainers Postgres). A
 * matched/not-pending payment's {@code reservation_id} is a real foreign key (V1__schema.sql), so every fixture
 * here that is not {@code UNMATCHED_FORMAT}/{@code UNMATCHED_UNKNOWN_RESERVATION} first inserts the reservation it
 * points at.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({TestcontainersConfiguration.class, JpaReservationRepositoryAdapter.class, JpaPropertyCatalogAdapter.class,
        JpaReceivedPaymentRepositoryAdapter.class})
class ReceivedPaymentPersistenceTest {

    private static final Instant SOME_INSTANT = Instant.parse("2036-05-01T09:00:00Z");

    @Autowired
    private ReceivedPaymentRepository payments;

    @Autowired
    private ReservationRepository reservations;

    @Test
    void sumsOnlyMatchedPaymentsOfReservation() {
        String reservationId = "P0000900";
        reservations.add(reservation(reservationId, "AMS01", "101", LocalDate.parse("2036-05-01"), LocalDate.parse("2036-05-05")));

        payments.add(receivedPayment(reservationId, "AMS01", Money.eur("100.00"), PaymentMatchOutcome.MATCHED_PARTIAL, SOME_INSTANT));
        payments.add(receivedPayment(reservationId, "AMS01", Money.eur("140.00"), PaymentMatchOutcome.MATCHED_FULL, SOME_INSTANT.plusSeconds(60)));
        payments.add(receivedPayment(reservationId, "AMS01", Money.eur("50.00"), PaymentMatchOutcome.UNMATCHED_NOT_PENDING, SOME_INSTANT.plusSeconds(120)));

        assertThat(payments.sumMatched(ReservationId.of(reservationId))).isEqualTo(Money.eur("240.00"));

        String otherReservationId = "P0000901";
        reservations.add(reservation(otherReservationId, "AMS01", "102", LocalDate.parse("2036-05-01"), LocalDate.parse("2036-05-05")));
        assertThat(payments.sumMatched(ReservationId.of(otherReservationId))).isEqualTo(Money.zeroEur());
    }

    @Test
    void listsPaymentsByReservationWithoutReservationAndNotPendingPerProperty() {
        String amsReservationId = "P0000910";
        String rtmReservationId = "P0000911";
        reservations.add(reservation(amsReservationId, "AMS01", "201", LocalDate.parse("2036-06-01"), LocalDate.parse("2036-06-03")));
        reservations.add(reservation(rtmReservationId, "RTM01", "201", LocalDate.parse("2036-06-01"), LocalDate.parse("2036-06-03")));

        // Inserted out of receivedAt order, to prove the ordering comes from the query, not from insertion order.
        ReceivedPayment second = receivedPayment(amsReservationId, "AMS01", Money.eur("70.00"),
                PaymentMatchOutcome.MATCHED_PARTIAL, SOME_INSTANT.plusSeconds(60));
        payments.add(second);
        ReceivedPayment first = receivedPayment(amsReservationId, "AMS01", Money.eur("50.00"),
                PaymentMatchOutcome.MATCHED_PARTIAL, SOME_INSTANT);
        payments.add(first);
        ReceivedPayment amsNotPending = receivedPayment(amsReservationId, "AMS01", Money.eur("30.00"),
                PaymentMatchOutcome.UNMATCHED_NOT_PENDING, SOME_INSTANT.plusSeconds(120));
        payments.add(amsNotPending);

        ReceivedPayment rtmNotPending = receivedPayment(rtmReservationId, "RTM01", Money.eur("40.00"),
                PaymentMatchOutcome.UNMATCHED_NOT_PENDING, SOME_INSTANT);
        payments.add(rtmNotPending);

        ReceivedPayment unmatchedFormat = unmatchedFormatPayment(Money.eur("10.00"), SOME_INSTANT);
        payments.add(unmatchedFormat);
        ReceivedPayment unmatchedUnknown = receivedPayment(null, null, Money.eur("20.00"),
                PaymentMatchOutcome.UNMATCHED_UNKNOWN_RESERVATION, SOME_INSTANT);
        payments.add(unmatchedUnknown);

        assertThat(payments.findByReservation(ReservationId.of(amsReservationId)))
                .extracting(ReceivedPayment::paymentId)
                .containsExactly(first.paymentId(), second.paymentId(), amsNotPending.paymentId());

        // The DB is shared across this whole test run (and, with Testcontainers reuse, across runs); other tests'
        // own unmatched payments may already be sitting in this same unscoped, service-wide list, so this only
        // asserts what this test itself put there, never the full contents or a count.
        assertThat(payments.findWithoutReservation())
                .extracting(ReceivedPayment::paymentId)
                .contains(unmatchedFormat.paymentId(), unmatchedUnknown.paymentId());

        assertThat(payments.findNotPending("AMS01"))
                .extracting(ReceivedPayment::paymentId)
                .contains(amsNotPending.paymentId())
                .doesNotContain(rtmNotPending.paymentId());
    }

    @Test
    void receivedPaymentRoundTripsAllColumns() {
        String paymentId = UUID.randomUUID().toString();
        ReceivedPayment original = new ReceivedPayment(
                paymentId,
                null,
                null,
                "NL91ABNA0417164300",
                Money.eur("12.34"),
                "not a valid E2E + reservationId description",
                null,
                PaymentMatchOutcome.UNMATCHED_FORMAT,
                SOME_INSTANT);

        payments.add(original);

        List<ReceivedPayment> withoutReservation = payments.findWithoutReservation();
        assertThat(withoutReservation).filteredOn(p -> p.paymentId().equals(paymentId)).singleElement().isEqualTo(original);
    }

    private static ReceivedPayment unmatchedFormatPayment(Money amount, Instant receivedAt) {
        return new ReceivedPayment(UUID.randomUUID().toString(), null, null, "NL91ABNA0417164300", amount,
                "garbled, unreadable description", null, PaymentMatchOutcome.UNMATCHED_FORMAT, receivedAt);
    }

    private static ReceivedPayment receivedPayment(@Nullable String reservationId, @Nullable String propertyId,
            Money amount, PaymentMatchOutcome outcome, Instant receivedAt) {
        String e2eId = "E2EIDABCD";
        String description = reservationId == null ? e2eId + " UNKNOWN0" : e2eId + " " + reservationId;
        return new ReceivedPayment(UUID.randomUUID().toString(),
                reservationId == null ? null : ReservationId.of(reservationId),
                propertyId, "NL91ABNA0417164300", amount, description, e2eId, outcome, receivedAt);
    }

    /** A minimal, always-CASH reservation: only needed here to satisfy received_payment's FK on reservation_id. */
    private static Reservation reservation(String reservationId, String propertyId, String roomNumber, LocalDate start, LocalDate end) {
        return Reservation.rehydrate(new ReservationState(
                UUID.randomUUID(),
                ReservationId.of(reservationId),
                propertyId,
                roomNumber,
                "Ada Lovelace",
                new StayPeriod(start, end),
                RoomSegment.MEDIUM,
                PaymentMode.CASH,
                null,
                ReservationStatus.CONFIRMED,
                null,
                Money.eur("240.00"),
                Money.eur("240.00"),
                null,
                0L,
                SOME_INSTANT,
                SOME_INSTANT));
    }
}
