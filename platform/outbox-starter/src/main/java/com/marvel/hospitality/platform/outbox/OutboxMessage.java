package com.marvel.hospitality.platform.outbox;

import org.jspecify.annotations.Nullable;

/**
 * One row to append to {@code outbox_event} (docs/contracts/outbox-and-inbox.md).
 *
 * @param aggregateType the outbox {@code aggregate_type}, e.g. {@code "reservation"}
 * @param aggregateId the outbox {@code aggregate_id}; becomes the Kafka message key
 * @param eventType the outbox {@code event_type}; becomes the {@code eventType} Kafka header
 * @param eventVersion the outbox {@code event_version}; start new event types at {@code 1}
 * @param topic the routing target read by Debezium's Outbox Event Router (ADR-0007)
 * @param propertyId the {@code propertyId} column/header; {@code null} only for the bank topic, which the brief
 *        defines without a {@code propertyId} (ADR-0002)
 * @param payload the event body; serialised to the {@code payload} jsonb column as-is
 */
public record OutboxMessage(
        String aggregateType, String aggregateId, String eventType, int eventVersion, String topic,
        @Nullable String propertyId, Object payload) {

    public OutboxMessage {
        requireNonBlank(aggregateType, "aggregateType");
        requireNonBlank(aggregateId, "aggregateId");
        requireNonBlank(eventType, "eventType");
        requireNonBlank(topic, "topic");
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
    }

    /** Convenience constructor for the common case: a new event type always starts at {@code eventVersion = 1}. */
    public OutboxMessage(
            String aggregateType, String aggregateId, String eventType, String topic,
            @Nullable String propertyId, Object payload) {
        this(aggregateType, aggregateId, eventType, 1, topic, propertyId, payload);
    }

    private static void requireNonBlank(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
