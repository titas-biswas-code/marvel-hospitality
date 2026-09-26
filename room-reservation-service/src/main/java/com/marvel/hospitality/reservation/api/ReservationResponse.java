package com.marvel.hospitality.reservation.api;

import com.marvel.hospitality.reservation.application.ReservationView;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationStatus;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * The reservation body returned by {@code POST}/{@code GET} (rest-api.md). Field order matches the contract exactly,
 * since records serialise in declaration order. {@code paymentDeadlineAt} and {@code bankTransferInstructions} are
 * {@code null} unless {@link PaymentMode#BANK_TRANSFER} — Jackson renders them as JSON {@code null}, not omitting
 * the key, because this service does not set {@code NON_NULL} inclusion.
 */
public record ReservationResponse(
        String reservationId,
        String propertyId,
        ReservationStatus status,
        String customerName,
        String roomNumber,
        RoomSegment roomSegment,
        LocalDate startDate,
        LocalDate endDate,
        int nights,
        PaymentMode paymentMode,
        @Nullable String paymentReference,
        BigDecimal totalAmount,
        BigDecimal amountReceived,
        String currency,
        @Nullable Instant paymentDeadlineAt,
        @Nullable String bankTransferInstructions,
        Instant createdAt,
        Instant updatedAt) {

    public static ReservationResponse from(ReservationView view) {
        Reservation reservation = view.reservation();
        return new ReservationResponse(
                reservation.reservationId().value(),
                reservation.propertyId(),
                reservation.status(),
                reservation.customerName(),
                reservation.roomNumber(),
                reservation.roomSegment(),
                reservation.stay().startDate(),
                reservation.stay().endDate(),
                reservation.stay().nights(),
                reservation.paymentMode(),
                reservation.paymentReference(),
                reservation.totalAmount().amount(),
                reservation.amountReceived().amount(),
                reservation.totalAmount().currency(),
                reservation.paymentDeadlineAt(),
                view.bankTransferInstructions(),
                reservation.createdAt(),
                reservation.updatedAt());
    }
}
