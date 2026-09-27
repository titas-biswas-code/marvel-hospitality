package com.marvel.hospitality.notification.infrastructure.kafka;

import com.marvel.hospitality.notification.application.RecordNotificationCommand;
import com.marvel.hospitality.notification.application.RecordNotificationUseCase;
import com.marvel.hospitality.platform.observability.LoggingContext;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code reservation-status-changed} (events.md). The platform Kafka starter supplies the policy (ADR-0008):
 * JSON is converted and validated before this method runs, technical failures are retried and then dead-lettered.
 * This class hands the event to {@link RecordNotificationUseCase}, whose transaction has committed when it returns,
 * and acknowledges afterwards, so a crash in between redelivers the record and the inbox turns the redelivery into a
 * no-op.
 *
 * <p>The dedupe key is the Kafka header {@code id} (outbox-and-inbox.md: the outbox row's id, emitted by Debezium's
 * Outbox Event Router). It is read from the raw record, not with {@code @Header("id")}: {@code id} is Spring
 * Messaging's reserved {@code MessageHeaders.ID}, so the converted message's {@code id} is not guaranteed to be the
 * Kafka header's value.
 */
@Component
class ReservationStatusChangedListener {

    static final String TOPIC = "reservation-status-changed";
    static final String LISTENER_ID = "reservation-status-changed";
    static final String EVENT_ID_HEADER = "id";

    private final RecordNotificationUseCase recordNotification;

    ReservationStatusChangedListener(RecordNotificationUseCase recordNotification) {
        this.recordNotification = recordNotification;
    }

    // idIsGroup = false: the id names the container (tests look it up); the group stays spring.kafka.consumer.group-id.
    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = TOPIC)
    void on(@Valid @Payload ReservationStatusChangedMessage message, ConsumerRecord<String, String> record,
            Acknowledgment ack) {
        try (LoggingContext ignored = LoggingContext.create()
                .reservationId(message.reservationId())
                .propertyId(message.propertyId())) {
            recordNotification.record(new RecordNotificationCommand(eventId(record), message.toNotice()));
            ack.acknowledge();
        }
    }

    /**
     * @throws IllegalArgumentException when the header is missing, empty or not a UUID: a contract violation, which
     *         the platform error handler dead-letters without retrying (ADR-0008)
     */
    static UUID eventId(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(EVENT_ID_HEADER);
        if (header == null || header.value() == null || header.value().length == 0) {
            throw new IllegalArgumentException(TOPIC + " record without header '" + EVENT_ID_HEADER + "' (key "
                    + record.key() + ", partition " + record.partition() + ", offset " + record.offset() + ")");
        }
        String value = new String(header.value(), StandardCharsets.UTF_8);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(TOPIC + " header '" + EVENT_ID_HEADER + "' is not a UUID: " + value, e);
        }
    }
}
