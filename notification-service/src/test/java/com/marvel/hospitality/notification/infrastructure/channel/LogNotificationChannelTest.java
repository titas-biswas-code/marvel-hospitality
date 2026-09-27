package com.marvel.hospitality.notification.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.marvel.hospitality.notification.domain.Notification;
import com.marvel.hospitality.notification.domain.NotificationTemplate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class LogNotificationChannelTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(LogNotificationChannel.class);
    private final List<Captured> captured = new ArrayList<>();
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent event) {
            // The MDC is read here, while the event is being logged; it is gone once deliver() returns.
            captured.add(new Captured(event.getLevel(), event.getFormattedMessage(), Map.copyOf(event.getMDCPropertyMap())));
        }
    };

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void logsNotificationAtInfoWithReservationAndPropertyInMdc() {
        Notification notification = new Notification(UUID.randomUUID(), UUID.randomUUID(), "P4145478", "AMS01",
                LogNotificationChannel.NAME, NotificationTemplate.RESERVATION_CONFIRMED, "Dear Ada Lovelace, ...",
                Instant.parse("2026-10-01T09:15:04Z"));

        new LogNotificationChannel().deliver(notification);

        assertThat(captured).singleElement().satisfies(event -> {
            assertThat(event.level()).isEqualTo(Level.INFO);
            assertThat(event.message()).contains("RESERVATION_CONFIRMED").contains("P4145478")
                    .contains("Dear Ada Lovelace, ...");
            assertThat(event.mdc()).containsEntry("reservationId", "P4145478").containsEntry("propertyId", "AMS01");
        });
        assertThat(MDC.get("reservationId")).isNull();
    }

    private record Captured(Level level, String message, Map<String, String> mdc) {
    }
}
