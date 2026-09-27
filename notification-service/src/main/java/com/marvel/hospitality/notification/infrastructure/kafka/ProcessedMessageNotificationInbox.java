package com.marvel.hospitality.notification.infrastructure.kafka;

import com.marvel.hospitality.notification.application.NotificationInbox;
import com.marvel.hospitality.platform.inbox.ProcessedMessageInbox;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link NotificationInbox} on the platform inbox ({@code processed_message}). The dedupe key of
 * {@code reservation-status-changed} is the header {@code id} (outbox-and-inbox.md).
 *
 * <p>The consumer name is a fixed name of this consumer, deliberately not the Kafka consumer group: a group can be
 * renamed or split (ADR-0008), and the inbox must still recognise every message it has already applied. Never change
 * {@link #CONSUMER}; stored rows are keyed by it.
 */
@Component
class ProcessedMessageNotificationInbox implements NotificationInbox {

    static final String CONSUMER = "reservation-status-changed";

    private final ProcessedMessageInbox inbox;

    ProcessedMessageNotificationInbox(ProcessedMessageInbox inbox) {
        this.inbox = inbox;
    }

    @Override
    public boolean firstDelivery(UUID eventId) {
        return inbox.markProcessed(eventId.toString(), CONSUMER, ReservationStatusChangedListener.TOPIC);
    }
}
