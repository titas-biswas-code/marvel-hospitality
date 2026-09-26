package com.marvel.hospitality.reservation.api;

import com.marvel.hospitality.reservation.application.CreateReservationCommand;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * The {@code POST /properties/{propertyId}/reservations} request body (rest-api.md). Only presence and size are
 * checked here; date relations (end after start, at most 30 nights, not in the past) are domain rules
 * ({@code InvalidStayException}) and are deliberately not duplicated as annotations. The "paymentReference required
 * for CREDIT_CARD" rule is PR-03 scope and is not enforced here either.
 */
public record CreateReservationRequest(
        @NotBlank @Size(max = 200) String customerName,
        @NotBlank @Size(max = 10) String roomNumber,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotNull RoomSegment roomSegment,
        @NotNull PaymentMode paymentMode,
        @Size(max = 64) @Nullable String paymentReference) {

    public CreateReservationCommand toCommand(String propertyId) {
        return new CreateReservationCommand(
                propertyId, customerName, roomNumber, startDate, endDate, roomSegment, paymentMode, paymentReference);
    }
}
