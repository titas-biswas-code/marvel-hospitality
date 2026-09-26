package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.CancellationReason;
import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.Reservation;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Cancels one overdue bank-transfer reservation: the saga-timeout step of ADR-0006, driven by
 * the auto-cancel job per ADR-0010.
 *
 * <p>{@code requiresNew} is built by the caller as its own {@link TransactionOperations} with
 * {@code PROPAGATION_REQUIRES_NEW} (never the service's default {@code TransactionTemplate}), so this use case
 * always runs in its own transaction — independent of whatever transaction, if any, happens to be open on the
 * calling thread. That is what lets the job cancel a whole page of rows one by one without one row's
 * failure rolling back another, and it is also what makes {@link OverduePaymentReservationQuery#claimIfDue}'s row
 * lock safe: the lock is taken and released within this one small transaction, never held across the unlocked page
 * read that found the candidate in the first place.
 */
public class CancelOverdueReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelOverdueReservationUseCase.class);

    private final OverduePaymentReservationQuery overdue;
    private final ReservationRepository reservations;
    private final OutboxWriter outbox;
    private final TransactionOperations requiresNew;
    private final Clock clock;

    public CancelOverdueReservationUseCase(OverduePaymentReservationQuery overdue, ReservationRepository reservations,
            OutboxWriter outbox, TransactionOperations requiresNew, Clock clock) {
        this.overdue = overdue;
        this.reservations = reservations;
        this.outbox = outbox;
        this.requiresNew = requiresNew;
        this.clock = clock;
    }

    /**
     * Claims row {@code id} and, if it is still due, cancels it. Empty means the row was skipped — locked by
     * another instance or by the payment consumer, or no longer due — never that something went wrong; the caller
     * (the scheduler) simply tries again on its next run.
     */
    public Optional<CancelledReservation> cancelIfOverdue(UUID id, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");
        return Objects.requireNonNull(requiresNew.execute(status -> cancelInTransaction(id, now)));
    }

    private Optional<CancelledReservation> cancelInTransaction(UUID id, Instant now) {
        Optional<Reservation> claimed = overdue.claimIfDue(id, now);
        if (claimed.isEmpty()) {
            log.debug("Reservation row {} skipped: locked elsewhere or no longer due", id);
            return Optional.empty();
        }
        Reservation reservation = claimed.get();
        try (MDC.MDCCloseable ignoredReservationId =
                     MDC.putCloseable("reservationId", reservation.reservationId().value());
                MDC.MDCCloseable ignoredPropertyId = MDC.putCloseable("propertyId", reservation.propertyId())) {
            reservation.cancel(CancellationReason.PAYMENT_DEADLINE_MISSED, clock);
            reservations.update(reservation);
            reservation.pullEvents().forEach(outbox::append);
            log.info("Reservation {} cancelled: payment deadline {} missed (received {} of {})",
                    reservation.reservationId(), reservation.paymentDeadlineAt(),
                    format(reservation.amountReceived()), format(reservation.totalAmount()));
            return Optional.of(new CancelledReservation(reservation.reservationId(), reservation.propertyId()));
        }
    }

    private static String format(Money money) {
        return money.amount().toPlainString() + " " + money.currency();
    }
}
