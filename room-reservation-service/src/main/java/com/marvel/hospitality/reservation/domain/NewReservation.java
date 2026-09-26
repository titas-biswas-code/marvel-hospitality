package com.marvel.hospitality.reservation.domain;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Everything an API request supplies to create a reservation, before any domain validation runs. */
public record NewReservation(
        ReservationId reservationId,
        String customerName,
        LocalDate startDate,
        LocalDate endDate,
        RoomSegment requestedSegment,
        PaymentMode paymentMode,
        @Nullable String paymentReference) {
}
