package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import com.marvel.hospitality.reservation.domain.ReservationId;
import java.util.List;

/**
 * Persistence port for {@code received_payment}: every bank payment this service has seen, matched or not (ADR-0009).
 * Rows are insert-only; implementations run inside the caller's transaction.
 */
public interface ReceivedPaymentRepository {

    void add(ReceivedPayment payment);

    /**
     * The sum of the reservation's matched payments ({@code MATCHED_PARTIAL}, {@code MATCHED_FULL}, {@code OVERPAID}),
     * zero when there are none. This is the source of truth for "how much has been paid"; the reservation's
     * {@code amountReceived} is a copy of it (ADR-0009). Payments that arrived after the reservation stopped waiting
     * ({@code UNMATCHED_NOT_PENDING}) are refunded, so they never count.
     */
    Money sumMatched(ReservationId reservationId);

    /** Every payment linked to the reservation, oldest first, whatever its outcome. */
    List<ReceivedPayment> findByReservation(ReservationId reservationId);

    /**
     * Payments that could not be linked to any reservation ({@code UNMATCHED_FORMAT},
     * {@code UNMATCHED_UNKNOWN_RESERVATION}), oldest first. They have no property: the bank topic carries none.
     */
    List<ReceivedPayment> findWithoutReservation();

    /** The property's payments for reservations that were no longer awaiting payment ({@code UNMATCHED_NOT_PENDING}). */
    List<ReceivedPayment> findNotPending(String propertyId);
}
