package com.marvel.hospitality.reservation.infrastructure.outbox;

import com.marvel.hospitality.platform.outbox.OutboxEventWriter;
import com.marvel.hospitality.platform.outbox.OutboxMessage;
import com.marvel.hospitality.reservation.application.OutboxWriter;
import com.marvel.hospitality.reservation.domain.RefundRequested;
import com.marvel.hospitality.reservation.domain.ReservationStatusChanged;
import org.springframework.stereotype.Component;

/**
 * {@link OutboxWriter} for this service's domain events: turns a {@link ReservationStatusChanged} or
 * {@link RefundRequested} into one {@code outbox_event} row via the platform {@link OutboxEventWriter}
 * (ADR-0006/0007), inside whatever transaction the caller (e.g. {@code CreateReservationUseCase}) is already running.
 * Never calls {@code KafkaTemplate} itself (ADR-0006) — Debezium is the only publisher.
 *
 * <p>Refund events are keyed by {@code paymentId} (events.md), so every event about one payment's refund lands on one
 * partition, in order.
 */
@Component
class ReservationOutboxWriter implements OutboxWriter {

    static final String AGGREGATE_TYPE = "reservation";
    static final String EVENT_TYPE = "ReservationStatusChanged";
    static final String TOPIC = "reservation-status-changed";
    static final String REFUND_AGGREGATE_TYPE = "refund";
    static final String REFUND_REQUESTED_EVENT_TYPE = "RefundRequested";
    static final String REFUND_REQUESTED_TOPIC = "refund-requested";

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

    @Override
    public void append(RefundRequested event) {
        outboxEventWriter.append(new OutboxMessage(REFUND_AGGREGATE_TYPE, event.paymentId(),
                REFUND_REQUESTED_EVENT_TYPE, REFUND_REQUESTED_TOPIC, event.propertyId(),
                RefundRequestedPayload.from(event)));
    }
}
