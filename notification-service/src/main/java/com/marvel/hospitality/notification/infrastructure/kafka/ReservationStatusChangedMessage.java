package com.marvel.hospitality.notification.infrastructure.kafka;

import com.marvel.hospitality.notification.domain.ReservationStatusNotice;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * The value of {@code reservation-status-changed} (events.md). A value that breaks these constraints is a contract
 * violation: it goes to the DLT without retries (ADR-0008). Status, reason and payment mode are deliberately plain
 * strings (a tolerant reader): a value the producer adds later becomes an {@code UNKNOWN} notification, not a
 * dead letter. Unknown JSON fields are ignored for the same reason.
 */
record ReservationStatusChangedMessage(
        @NotBlank @Size(max = 8) String reservationId,
        @NotBlank @Size(max = 8) String propertyId,
        @NotBlank String customerName,
        @NotBlank String roomNumber,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotBlank String paymentMode,
        @Nullable String previousStatus,
        @NotBlank String status,
        @Nullable String reason,
        @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal totalAmount,
        @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal amountReceived,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code") String currency,
        @Nullable Instant paymentDeadlineAt,
        @NotNull Instant occurredAt) {

    ReservationStatusNotice toNotice() {
        return new ReservationStatusNotice(reservationId, propertyId, customerName, roomNumber, startDate, endDate,
                paymentMode, previousStatus, status, reason, totalAmount, amountReceived, currency, paymentDeadlineAt,
                occurredAt);
    }
}
