package com.marvel.hospitality.notification.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A rendered customer notification, one per {@code reservation-status-changed} event ({@code notification} table,
 * database-schemas.md).
 *
 * @param eventId the event's header {@code id}, the dedupe key of the topic (outbox-and-inbox.md)
 * @param channel the name of the channel it was handed to ({@code LOG}; an e-mail channel would add its own)
 */
public record Notification(
        UUID id,
        UUID eventId,
        String reservationId,
        String propertyId,
        String channel,
        NotificationTemplate template,
        String renderedText,
        Instant createdAt) {

    public static Notification of(UUID id, UUID eventId, ReservationStatusNotice notice,
            RenderedNotification rendered, String channel, Instant createdAt) {
        return new Notification(id, eventId, notice.reservationId(), notice.propertyId(), channel, rendered.template(),
                rendered.text(), createdAt);
    }
}
