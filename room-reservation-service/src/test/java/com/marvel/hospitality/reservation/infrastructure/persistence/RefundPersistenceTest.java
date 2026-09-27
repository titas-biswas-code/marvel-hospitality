package com.marvel.hospitality.reservation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.marvel.hospitality.platform.problem.ConstraintNames;
import com.marvel.hospitality.reservation.TestcontainersConfiguration;
import com.marvel.hospitality.reservation.application.ReceivedPaymentRepository;
import com.marvel.hospitality.reservation.application.RefundRepository;
import com.marvel.hospitality.reservation.application.ReservationRepository;
import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.PaymentMatchOutcome;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import com.marvel.hospitality.reservation.domain.Refund;
import com.marvel.hospitality.reservation.domain.RefundDue;
import com.marvel.hospitality.reservation.domain.RefundReason;
import com.marvel.hospitality.reservation.domain.RefundStatus;
import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationId;
import com.marvel.hospitality.reservation.domain.ReservationIdGenerator;
import com.marvel.hospitality.reservation.domain.ReservationState;
import com.marvel.hospitality.reservation.domain.ReservationStatus;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import com.marvel.hospitality.reservation.domain.StayPeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * {@link JpaRefundRepositoryAdapter} against a real Postgres, sibling to {@link ReceivedPaymentPersistenceTest}
 * (identical slice annotations, one cached context). {@code refund} references both {@code received_payment} and
 * {@code reservation}, so every fixture first stores the reservation and the payment the refund belongs to. Enums go
 * through the {@code reason} and {@code status} check constraints as their names. The persistence context is cleared
 * before every read, so what is asserted came from the table, not from Hibernate's first-level cache.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({TestcontainersConfiguration.class, JpaReservationRepositoryAdapter.class, JpaPropertyCatalogAdapter.class,
        JpaReceivedPaymentRepositoryAdapter.class, JpaRefundRepositoryAdapter.class})
class RefundPersistenceTest {

    private static final Instant REQUESTED_AT = Instant.parse("2038-05-01T09:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2038-05-01T09:00:05Z");
    /** LIS01 room 401 in 2038, a week per fixture: no other test books it. */
    private static final LocalDate FIRST_STAY = LocalDate.parse("2038-06-04");
    private static final AtomicInteger STAYS = new AtomicInteger();

    @Autowired
    private RefundRepository refunds;

    @Autowired
    private ReceivedPaymentRepository payments;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void requestedRefundRoundTripsAllColumns() {
        Refund requested = requestedRefund(Money.eur("30.00"), RefundReason.OVERPAYMENT);

        refunds.add(requested);
        entityManager.clear();

        assertThat(refunds.findById(requested.refundId())).get()
                .usingRecursiveComparison().ignoringFields("events").isEqualTo(requested);
    }

    @Test
    void completedOutcomeIsStored() {
        Refund refund = requestedRefund(Money.eur("30.00"), RefundReason.OVERPAYMENT);
        refunds.add(refund);

        refund.complete(COMPLETED_AT);
        refunds.update(refund);
        entityManager.clear();

        Refund stored = refunds.findById(refund.refundId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(stored.completedAt()).isEqualTo(COMPLETED_AT);
        assertThat(stored.failureReason()).isNull();
    }

    @Test
    void failedOutcomeKeepsItsReason() {
        Refund refund = requestedRefund(Money.eur("240.00"), RefundReason.RESERVATION_CANCELLED);
        refunds.add(refund);

        refund.fail("CREDITOR_ACCOUNT_REJECTED", COMPLETED_AT);
        refunds.update(refund);
        entityManager.clear();

        Refund stored = refunds.findById(refund.refundId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(stored.reason()).isEqualTo(RefundReason.RESERVATION_CANCELLED);
        assertThat(stored.failureReason()).isEqualTo("CREDITOR_ACCOUNT_REJECTED");
        assertThat(stored.completedAt()).isEqualTo(COMPLETED_AT);
    }

    @Test
    void findsRefundsOfTheGivenPaymentsOnly() {
        Refund first = requestedRefund(Money.eur("10.00"), RefundReason.OVERPAYMENT);
        Refund second = requestedRefund(Money.eur("20.00"), RefundReason.OVERPAYMENT);
        Refund other = requestedRefund(Money.eur("30.00"), RefundReason.OVERPAYMENT);
        refunds.add(first);
        refunds.add(second);
        refunds.add(other);
        entityManager.clear();

        List<Refund> found = refunds.findByPaymentIds(List.of(first.paymentId(), second.paymentId(), "no-such-payment"));

        assertThat(found).extracting(Refund::refundId).containsExactlyInAnyOrder(first.refundId(), second.refundId());
        assertThat(refunds.findByPaymentIds(List.of())).isEmpty();
    }

    /** V3: a second refund for one payment is refused by the database itself, by this constraint name. */
    @Test
    void aPaymentCannotHaveTwoRefunds() {
        Refund first = requestedRefund(Money.eur("10.00"), RefundReason.OVERPAYMENT);
        refunds.add(first);
        Refund second = Refund.rehydrate(UUID.randomUUID(), first.paymentId(), first.reservationId(),
                first.propertyId(), Money.eur("5.00"), RefundReason.OVERPAYMENT, RefundStatus.REQUESTED, null,
                REQUESTED_AT, null);

        Throwable failure = catchThrowable(() -> refunds.add(second));

        assertThat(failure).isNotNull();
        assertThat(ConstraintNames.of(failure)).contains("refund_payment_id_key");
    }

    @Test
    void unknownRefundIsNotFound() {
        assertThat(refunds.findById(UUID.randomUUID())).isEmpty();
    }

    /** A stored CASH reservation and a not-pending payment on it, and the refund that payment makes due. */
    private Refund requestedRefund(Money amount, RefundReason reason) {
        ReservationId reservationId = new ReservationIdGenerator().next();
        LocalDate start = FIRST_STAY.plusWeeks(STAYS.getAndIncrement());
        reservations.add(Reservation.rehydrate(new ReservationState(UUID.randomUUID(), reservationId, "LIS01", "401",
                "Ada Lovelace", new StayPeriod(start, start.plusDays(2)), RoomSegment.EXTRA_LARGE, PaymentMode.CASH,
                null, ReservationStatus.CONFIRMED, null, Money.eur("520.00"), Money.eur("520.00"), null, 0L,
                REQUESTED_AT, REQUESTED_AT)));
        ReceivedPayment payment = new ReceivedPayment(UUID.randomUUID().toString(), reservationId, "LIS01",
                "NL91ABNA0417164300", amount, "E2E0000001 " + reservationId.value(), "E2E0000001",
                PaymentMatchOutcome.UNMATCHED_NOT_PENDING, REQUESTED_AT);
        payments.add(payment);
        Refund refund = Refund.request(UUID.randomUUID(), payment, new RefundDue(amount, reason), REQUESTED_AT);
        refund.pullEvents();
        return refund;
    }
}
