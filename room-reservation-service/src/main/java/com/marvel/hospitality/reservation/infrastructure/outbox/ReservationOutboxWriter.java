package com.marvel.hospitality.reservation.infrastructure.outbox;

import com.marvel.hospitality.platform.outbox.OutboxEventWriter;
import com.marvel.hospitality.platform.outbox.OutboxMessage;
import com.marvel.hospitality.reservation.application.OutboxWriter;
import com.marvel.hospitality.reservation.domain.ReservationStatusChanged;
import org.springframework.stereotype.Component;

/**
 * {@link OutboxWriter} for the reservation aggregate: turns a {@link ReservationStatusChanged} domain event into
 * one {@code outbox_event} row via the platform {@link OutboxEventWriter} (ADR-0006/0007), inside whatever
 * transaction the caller (e.g. {@code CreateReservationUseCase}) is already running. Never calls
 * {@code KafkaTemplate} itself (ADR-0006) — Debezium is the only publisher.
 */
@Component
class ReservationOutboxWriter implements OutboxWriter {

    static final String AGGREGATE_TYPE = "reservation";
    static final String EVENT_TYPE = "ReservationStatusChanged";
    static final String TOPIC = "reservation-status-changed";

    private final OutboxEventWriter outboxEventWriter;

    ReservationOutboxWriter(OutboxEventWriter outboxEventWriter) {
        this.outboxEventWriter = outboxEventWriter;
    }

    @Override
    public void append(ReservationStatusChanged event) {
        ReservationStatusChangedPayload payload = ReservationStatusChangedPayload.from(event);
        outboxEventWriter.append(new OutboxMessage(
                AGGREGATE_TYPE, event.reservationId().value(), EVENT_TYPE, TOPIC, event.propertyId(), payload));
    }
}
