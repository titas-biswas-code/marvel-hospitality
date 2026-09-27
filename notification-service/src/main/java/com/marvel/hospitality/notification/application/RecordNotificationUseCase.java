package com.marvel.hospitality.notification.application;

import com.marvel.hospitality.notification.domain.Notification;
import com.marvel.hospitality.notification.domain.NotificationRenderer;
import com.marvel.hospitality.notification.domain.NotificationTemplate;
import com.marvel.hospitality.notification.domain.RenderedNotification;
import com.marvel.hospitality.notification.domain.ReservationStatusNotice;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The {@code reservation-status-changed} consumer's use case: one local transaction marks the event processed and
 * stores its rendered notification (ADR-0006), then, after the commit, the notification is handed to the
 * {@link NotificationChannel}.
 *
 * <p>Delivering after the commit means a rolled-back notification is never sent, and no transaction waits on a
 * channel. The price: a crash between the commit and {@link NotificationChannel#deliver} loses that one delivery
 * (the row exists, the redelivered event is a duplicate). Acceptable for a log line; a real e-mail channel would add
 * a delivery status to the row and a job that sends what is still pending, making delivery at-least-once.
 */
@Service
public class RecordNotificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordNotificationUseCase.class);

    private final NotificationInbox inbox;
    private final NotificationRepository notifications;
    private final NotificationChannel channel;
    private final TransactionOperations transactions;
    private final Clock clock;

    public RecordNotificationUseCase(NotificationInbox inbox, NotificationRepository notifications,
            NotificationChannel channel, TransactionOperations transactions, Clock clock) {
        this.inbox = inbox;
        this.notifications = notifications;
        this.channel = channel;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** @return the stored notification, or empty when the event was already processed (a redelivery) */
    public Optional<Notification> record(RecordNotificationCommand command) {
        Optional<Notification> stored = storeOnce(command);
        stored.ifPresent(channel::deliver);
        return stored;
    }

    private Optional<Notification> storeOnce(RecordNotificationCommand command) {
        ReservationStatusNotice notice = command.notice();
        try (MDC.MDCCloseable ignoredReservationId = MDC.putCloseable("reservationId", notice.reservationId());
                MDC.MDCCloseable ignoredPropertyId = MDC.putCloseable("propertyId", notice.propertyId())) {
            Optional<Notification> stored = Objects.requireNonNull(transactions.execute(status -> {
                if (!inbox.firstDelivery(command.eventId())) {
                    log.debug("Status event {} already processed; duplicate delivery skipped", command.eventId());
                    return Optional.empty();
                }
                RenderedNotification rendered = NotificationRenderer.render(notice);
                Notification notification = Notification.of(UUID.randomUUID(), command.eventId(), notice, rendered,
                        channel.name(), clock.instant());
                notifications.add(notification);
                return Optional.of(notification);
            }));
            stored.filter(notification -> notification.template() == NotificationTemplate.UNKNOWN)
                    .ifPresent(notification -> log.warn(
                            "No template for status event {} (previousStatus={}, status={}, reason={}); stored as {}",
                            command.eventId(), notice.previousStatus(), notice.status(), notice.reason(),
                            NotificationTemplate.UNKNOWN));
            return stored;
        }
    }
}
