package com.marvel.hospitality.reservation.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link PaymentModeHandler} decides a brand-new reservation's status — and, for bank transfer, payment
 * deadline — should be.
 */
public record InitialOutcome(ReservationStatus status, @Nullable Instant paymentDeadlineAt) {
}
