package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.ReservationStatusChanged;

/**
 * Turns domain events into outbox rows. Must be called inside the transaction that changed the aggregate, so the
 * state change and its event commit or roll back together (ADR-0006); Debezium publishes the rows (ADR-0007).
 * The application never publishes to Kafka itself.
 */
public interface OutboxWriter {

    void append(ReservationStatusChanged event);
}
