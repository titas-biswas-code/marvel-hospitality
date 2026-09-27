package com.marvel.hospitality.notification.application;

import java.util.UUID;

/**
 * Idempotency for {@code reservation-status-changed} (ADR-0006, outbox-and-inbox.md): remembers each event id (header
 * {@code id}) this service has processed. Must be called inside the transaction that stores the notification, so the
 * record and the effect commit together.
 */
public interface NotificationInbox {

    /** @return {@code true} the first time {@code eventId} is seen; {@code false} for a redelivery */
    boolean firstDelivery(UUID eventId);
}
