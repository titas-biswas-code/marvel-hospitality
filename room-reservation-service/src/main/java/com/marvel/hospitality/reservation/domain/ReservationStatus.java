package com.marvel.hospitality.reservation.domain;

/**
 * The reservation lifecycle (ADR-0004). Fixed by the brief; the full transition table lives on
 * {@link Reservation}, not here. Served to UIs via {@code GET /reference-data} (rest-api.md) so nothing
 * hardcodes these names; persisted as {@code varchar} with a DB {@code CHECK} — never {@code ORDINAL}, never
 * a Postgres {@code ENUM} type, so widening this set is a deliberate code change plus a migration.
 */
public enum ReservationStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CANCELLED
}
