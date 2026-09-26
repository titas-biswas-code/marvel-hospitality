package com.marvel.hospitality.reservation.domain;

import java.time.Instant;

/** Everything a {@link PaymentModeHandler} needs to decide the {@link InitialOutcome} of a new reservation. */
public record PaymentModeContext(Property property, StayPeriod stay, Money totalAmount, Instant now) {
}
