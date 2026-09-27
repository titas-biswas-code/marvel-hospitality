package com.marvel.hospitality.notification.infrastructure.channel;

import com.marvel.hospitality.notification.application.NotificationChannel;
import com.marvel.hospitality.notification.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * "Sends" a notification by logging it at INFO, with {@code reservationId} and {@code propertyId} in the MDC so the
 * structured log line can be found by either. Nothing is delivered to the customer: the event carries no contact
 * details (README).
 */
@Component
class LogNotificationChannel implements NotificationChannel {

    static final String NAME = "LOG";

    private static final Logger log = LoggerFactory.getLogger(LogNotificationChannel.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void deliver(Notification notification) {
        try (MDC.MDCCloseable ignoredReservationId = MDC.putCloseable("reservationId", notification.reservationId());
                MDC.MDCCloseable ignoredPropertyId = MDC.putCloseable("propertyId", notification.propertyId())) {
            log.info("Notification {} ({}) for reservation {}:\n{}", notification.id(), notification.template(),
                    notification.reservationId(), notification.renderedText());
        }
    }
}
