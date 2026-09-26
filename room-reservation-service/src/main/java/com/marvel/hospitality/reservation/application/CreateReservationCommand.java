package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Input of {@link CreateReservationUseCase}; the API layer has already checked presence and sizes. */
public record CreateReservationCommand(
        String propertyId,
        String customerName,
        String roomNumber,
        LocalDate startDate,
        LocalDate endDate,
        RoomSegment roomSegment,
        PaymentMode paymentMode,
        @Nullable String paymentReference) {
}
