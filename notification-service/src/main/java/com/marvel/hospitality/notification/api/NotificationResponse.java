package com.marvel.hospitality.notification.api;

import com.marvel.hospitality.notification.domain.Notification;
import java.time.Instant;

/** One element of the {@code GET /notifications} body (rest-api.md). */
public record NotificationResponse(
        String id,
        String eventId,
        String reservationId,
        String propertyId,
        String channel,
        String template,
        String renderedText,
        Instant createdAt) {

    static NotificationResponse from(Notification notification) {
        return new NotificationResponse(notification.id().toString(), notification.eventId().toString(),
                notification.reservationId(), notification.propertyId(), notification.channel(),
                notification.template().name(), notification.renderedText(), notification.createdAt());
    }
}
