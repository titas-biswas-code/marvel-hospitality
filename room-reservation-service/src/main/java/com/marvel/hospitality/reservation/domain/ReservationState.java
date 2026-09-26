package com.marvel.hospitality.reservation.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A flat snapshot of every persisted reservation field, used only to rehydrate a {@link Reservation} from
 * storage ({@link Reservation#rehydrate}) and to read one back for saving ({@link Reservation#snapshot}).
 * Keeps JPA (or any other persistence technology) out of the domain package: infrastructure maps its entity
 * to/from this record, never the other way around.
 */
public record ReservationState(
        UUID id,
        ReservationId reservationId,
        String propertyId,
        String roomNumber,
        String customerName,
        StayPeriod stay,
        RoomSegment roomSegment,
        PaymentMode paymentMode,
        @Nullable String paymentReference,
        ReservationStatus status,
        @Nullable CancellationReason cancellationReason,
        Money totalAmount,
        Money amountReceived,
        @Nullable Instant paymentDeadlineAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
