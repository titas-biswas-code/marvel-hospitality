package com.marvel.hospitality.notification.domain;

import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The reservation statuses this service knows how to notify about ({@code status}/{@code previousStatus} of
 * {@code reservation-status-changed}, events.md). The reservation service owns the list; a value added there later
 * must not make its events undeliverable here, so wire values are parsed tolerantly and an unknown one simply
 * selects {@link NotificationTemplate#UNKNOWN}.
 */
public enum ReservationStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CANCELLED;

    /** @return the status named by {@code value}, empty when {@code value} is null or not a status known here */
    public static Optional<ReservationStatus> fromWire(@Nullable String value) {
        return Arrays.stream(values()).filter(status -> status.name().equals(value)).findFirst();
    }
}
