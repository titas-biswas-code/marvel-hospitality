package com.marvel.hospitality.reservation.domain;

import java.util.Objects;

/**
 * A refund that {@link PaymentMatcher} decided is owed as a side effect of classifying an incoming payment
 * (ADR-0009): overpayment surplus, or a payment that landed on a reservation that is no longer
 * {@code PENDING_PAYMENT}. Creating the actual {@code Refund} aggregate and its {@code refund-requested} event
 * is application-layer/PR-07 work; this is just the domain's answer to "is a refund owed, how much, and why".
 */
public record RefundDue(Money amount, RefundReason reason) {

    public RefundDue {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(reason, "reason");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
    }
}
