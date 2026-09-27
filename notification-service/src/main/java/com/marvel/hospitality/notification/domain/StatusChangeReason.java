package com.marvel.hospitality.notification.domain;

import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The {@code reason} values of {@code reservation-status-changed} (events.md) this service knows. Parsed tolerantly
 * for the same reason as {@link ReservationStatus}.
 */
public enum StatusChangeReason {
    PAYMENT_RECEIVED,
    PARTIAL_PAYMENT_RECEIVED,
    PAYMENT_DEADLINE_MISSED;

    /** @return the reason named by {@code value}, empty when {@code value} is null or not a reason known here */
    public static Optional<StatusChangeReason> fromWire(@Nullable String value) {
        return Arrays.stream(values()).filter(reason -> reason.name().equals(value)).findFirst();
    }
}
