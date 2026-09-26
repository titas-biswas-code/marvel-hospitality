package com.marvel.hospitality.reservation.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * A hotel property. {@code timezone} drives both "today" for new-booking validation and the payment-deadline
 * formula (ADR-0010) — both must use the property's local calendar, never the server's or UTC's, since a
 * property's local midnight can fall on a different date than the server's "now".
 */
public record Property(String id, String name, ZoneId timezone, String bankAccountNumber) {

    public LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(timezone));
    }
}
