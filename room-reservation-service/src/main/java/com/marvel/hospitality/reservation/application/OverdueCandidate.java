package com.marvel.hospitality.reservation.application;

import java.time.Instant;
import java.util.UUID;

/**
 * One row {@link OverduePaymentReservationQuery#findDue} found due, and the keyset cursor a later call to it pages
 * forward from. Carries just enough to order and re-identify the row ({@code (payment_deadline_at, id)}, ADR-0010) —
 * never the full aggregate, since the page itself is read without a lock and must not be mistaken for something
 * safe to act on directly.
 */
public record OverdueCandidate(UUID id, Instant paymentDeadlineAt) {
}
