package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import com.marvel.hospitality.reservation.domain.RefundDue;

/**
 * What happens when money has to go back (ADR-0009: an overpayment's surplus, or a payment for a reservation that is
 * no longer awaiting payment). Called inside the payment's transaction, so an implementation that records a refund
 * and its {@code RefundRequested} outbox row commits or rolls back with the payment itself (ADR-0006).
 */
public interface RefundPolicy {

    /**
     * @param payment the payment the refund belongs to; always linked to a reservation (unmatched payments are never
     *        refunded automatically, ADR-0009)
     */
    void refundDue(ReceivedPayment payment, RefundDue refund);
}
