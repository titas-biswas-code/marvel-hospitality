package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.PaymentMode;

/**
 * A payment check that must succeed <em>before</em> a reservation may be stored, run by
 * {@link CreateReservationUseCase} outside any database transaction because it may call a remote service
 * (ADR-0011: a transaction is never held across a remote call). Registered per {@link PaymentMode}, like
 * {@code PaymentModeHandler}; a mode without one (cash, bank transfer) simply has nothing to verify up front.
 */
public interface PaymentVerification {

    PaymentMode mode();

    /**
     * Returns normally when the payment allows the reservation to be created.
     *
     * @throws PaymentRejectedException the payment exists but is not confirmed, or does not exist
     * @throws PaymentServiceUnavailableException the payment could not be checked right now
     */
    void verify(CreateReservationCommand command);
}
