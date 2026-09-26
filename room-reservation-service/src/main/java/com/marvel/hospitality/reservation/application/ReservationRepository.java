package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationId;
import com.marvel.hospitality.reservation.domain.StayPeriod;
import java.util.Optional;

/** Persistence port for the reservation aggregate. Implementations must run inside the caller's transaction. */
public interface ReservationRepository {

    /**
     * Inserts a new reservation and flushes, so constraint violations surface here rather than at commit.
     *
     * @throws RoomUnavailableException the exclusion constraint {@code reservation_no_overlap} rejected the stay
     * @throws ReservationIdCollisionException the business id is already taken (unique index)
     * @throws PaymentReferenceAlreadyUsedException a {@code CREDIT_CARD} payment reference already backs a reservation
     */
    void add(Reservation reservation);

    /** Looks up by property <em>and</em> id, so a reservation of another property is simply not found. */
    Optional<Reservation> find(String propertyId, ReservationId reservationId);

    /**
     * Whether a non-cancelled reservation already overlaps {@code stay} for the room — the same predicate as the
     * exclusion constraint {@code reservation_no_overlap}, but a plain read without locks. Only an early answer: a
     * concurrent insert can still win, and then {@link #add} throws {@link RoomUnavailableException} as usual.
     */
    boolean isBooked(String propertyId, String roomNumber, StayPeriod stay);

    /**
     * Whether a reservation paid with {@code mode} already carries {@code paymentReference}, in any property — the
     * predicate of the unique index {@code reservation_credit_card_payment_reference_uq} for card payments. Like
     * {@link #isBooked}, an early answer only; {@link #add} still enforces it.
     */
    boolean isPaymentReferenceUsed(PaymentMode mode, String paymentReference);
}
