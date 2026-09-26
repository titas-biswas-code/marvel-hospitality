package com.marvel.hospitality.reservation.infrastructure.outbox;

import com.marvel.hospitality.reservation.domain.ReservationStatusChanged;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * The exact {@code reservation-status-changed} value shape (events.md), field for field and in the same order.
 * Enums serialise as their plain names (Jackson's default for a {@code String}-typed record component fed an
 * {@code Enum.name()}), and nullable fields ({@code previousStatus}, {@code reason}, {@code paymentDeadlineAt})
 * must render as JSON {@code null} rather than being dropped — the contract's example shows
 * {@code "previousStatus": null} — so nothing here or upstream may set {@code NON_NULL} inclusion.
 */
public record ReservationStatusChangedPayload(
        String reservationId,
        String propertyId,
        String customerName,
        String roomNumber,
        LocalDate startDate,
        LocalDate endDate,
        String paymentMode,
        @Nullable String previousStatus,
        String status,
        @Nullable String reason,
        BigDecimal totalAmount,
        BigDecimal amountReceived,
        String currency,
        @Nullable Instant paymentDeadlineAt,
        Instant occurredAt) {

    public static ReservationStatusChangedPayload from(ReservationStatusChanged event) {
        return new ReservationStatusChangedPayload(
                event.reservationId().value(),
                event.propertyId(),
                event.customerName(),
                event.roomNumber(),
                event.startDate(),
                event.endDate(),
                event.paymentMode().name(),
                event.previousStatus() == null ? null : event.previousStatus().name(),
                event.status().name(),
                event.reason() == null ? null : event.reason().name(),
                event.totalAmount().amount(),
                event.amountReceived().amount(),
                event.totalAmount().currency(),
                event.paymentDeadlineAt(),
                event.occurredAt());
    }
}
