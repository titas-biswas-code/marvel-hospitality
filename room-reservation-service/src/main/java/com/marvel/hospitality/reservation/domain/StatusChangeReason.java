package com.marvel.hospitality.reservation.domain;

/**
 * The {@code reason} field of the {@code reservation-status-changed} event (events.md). Distinct from
 * {@link CancellationReason}: it also names why a status changed when the change is not a cancellation
 * (payment received) and, from PR-05, a same-status notice when a partial payment lands. It is not part of
 * {@code GET /reference-data} — it describes an event payload, not a persisted column.
 */
public enum StatusChangeReason {
    PAYMENT_RECEIVED,
    PARTIAL_PAYMENT_RECEIVED,
    PAYMENT_DEADLINE_MISSED
}
