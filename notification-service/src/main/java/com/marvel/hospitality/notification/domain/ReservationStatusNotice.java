package com.marvel.hospitality.notification.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * One {@code reservation-status-changed} event as this service sees it (events.md). Status, reason and payment mode
 * stay the producer's wire strings: {@link NotificationTemplate#select} interprets them, and a value it does not know
 * is still shown verbatim in the {@link NotificationTemplate#UNKNOWN} text.
 *
 * <p>The event carries no customer contact details (e-mail, phone) and no bank account; a real system would look
 * them up by reservation before sending anything (README).
 */
public record ReservationStatusNotice(
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
}
