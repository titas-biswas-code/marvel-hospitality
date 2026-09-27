package com.marvel.hospitality.notification.domain;

import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The {@code paymentMode} values of {@code reservation-status-changed} (events.md) this service knows. Parsed
 * tolerantly for the same reason as {@link ReservationStatus}.
 */
public enum PaymentMode {
    CASH,
    BANK_TRANSFER,
    CREDIT_CARD;

    /** @return the mode named by {@code value}, empty when {@code value} is null or not a mode known here */
    public static Optional<PaymentMode> fromWire(@Nullable String value) {
        return Arrays.stream(values()).filter(mode -> mode.name().equals(value)).findFirst();
    }
}
