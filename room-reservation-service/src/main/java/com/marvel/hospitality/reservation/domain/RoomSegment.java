package com.marvel.hospitality.reservation.domain;

/**
 * Room size tier. The segment *names* are an enum because the API contract uses them; the nightly *rates*
 * are data in {@code room_rate} because they vary per property and change often (ADR-0004). Served via
 * {@code GET /reference-data}; persisted as {@code varchar} + {@code CHECK}.
 */
public enum RoomSegment {
    SMALL,
    MEDIUM,
    LARGE,
    EXTRA_LARGE
}
