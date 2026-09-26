/**
 * The transactional outbox mechanism shared by every service that publishes domain events (ADR-0006,
 * docs/contracts/outbox-and-inbox.md): {@link com.marvel.hospitality.platform.outbox.OutboxEventWriter} appends an
 * {@code outbox_event} row in the caller's own transaction; Debezium's Outbox Event Router (ADR-0007) is what
 * actually publishes it to Kafka. The application itself never calls {@code KafkaTemplate} for a domain event.
 */
@NullMarked
package com.marvel.hospitality.platform.outbox;

import org.jspecify.annotations.NullMarked;
