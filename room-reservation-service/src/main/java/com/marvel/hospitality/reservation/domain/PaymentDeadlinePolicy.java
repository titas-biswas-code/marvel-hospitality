package com.marvel.hospitality.reservation.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The bank-transfer payment deadline formula (ADR-0010): local midnight two calendar days before the stay's
 * start date, in the property's own timezone. Computed once at creation and stored as data rather than
 * re-derived later, so a DST transition falling between "now" and the start date is baked into the stored
 * instant and every reader (scheduler, API) sees the same deadline.
 */
public final class PaymentDeadlinePolicy {

    public static final int DAYS_BEFORE_START = 2;

    public Instant deadlineFor(LocalDate startDate, ZoneId propertyZone) {
        return startDate.atStartOfDay(propertyZone).minusDays(DAYS_BEFORE_START).toInstant();
    }
}
