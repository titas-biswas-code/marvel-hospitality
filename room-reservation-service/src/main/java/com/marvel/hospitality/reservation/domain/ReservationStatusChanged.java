package com.marvel.hospitality.reservation.domain;

import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Domain event mirroring the {@code reservation-status-changed} Kafka contract (events.md) field for field, so
 * the application layer's outbox payload mapping is a straight copy rather than a translation. Emitted on
 * creation ({@code previousStatus == null}), on {@code PENDING_PAYMENT}→{@code CONFIRMED}/{@code CANCELLED},
 * and — from PR-05 — with {@code previousStatus == status} when a partial payment lands.
 */
public record ReservationStatusChanged(
        ReservationId reservationId,
        String propertyId,
        String customerName,
        String roomNumber,
        LocalDate startDate,
        LocalDate endDate,
        PaymentMode paymentMode,
        @Nullable ReservationStatus previousStatus,
        ReservationStatus status,
        @Nullable StatusChangeReason reason,
        Money totalAmount,
        Money amountReceived,
        @Nullable Instant paymentDeadlineAt,
        Instant occurredAt) {
}
