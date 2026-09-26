package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.Reservation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Finds and claims bank-transfer reservations whose payment deadline has passed while they are still
 * {@code PENDING_PAYMENT} (ADR-0010's due predicate: {@code status = 'PENDING_PAYMENT' AND
 * payment_mode = 'BANK_TRANSFER' AND payment_deadline_at <= now}, matching the partial index
 * {@code reservation_deadline_idx}).
 *
 * <p>Split into an unlocked page read ({@link #findDue}) and a locked per-row claim ({@link #claimIfDue}) on
 * purpose. The tempting alternative — lock the whole page with {@code FOR UPDATE SKIP LOCKED} and then cancel each
 * row in its own {@code REQUIRES_NEW} transaction — self-deadlocks: the outer transaction keeps holding the row
 * locks while the inner transaction, running on the very same thread, waits on them forever (ADR-0010's amendment).
 * Reading the page without any lock avoids that; the price is that a row can change between the read and the claim,
 * which {@link #claimIfDue} simply treats as "not due any more" rather than as an error.
 */
public interface OverduePaymentReservationQuery {

    /**
     * The next page of due rows, read without taking any lock, ordered by {@code (payment_deadline_at, id)}.
     * {@code after} is the last row the previous call to this method returned ({@code null} for the very first
     * page); a row is included only if it sorts strictly after {@code after} in that order, so repeated calls walk
     * forward through the whole due set — via a stable keyset, not an offset — even though nothing is locked
     * between one call and the next and rows keep leaving the set as they are cancelled.
     */
    List<OverdueCandidate> findDue(Instant now, @Nullable OverdueCandidate after, int limit);

    /**
     * Locks and re-validates one candidate ({@code SELECT ... FOR UPDATE SKIP LOCKED}) against the due predicate,
     * as of {@code now}. Must run inside the caller's own transaction (ADR-0010: {@code REQUIRES_NEW}, so that one
     * row's failure cannot roll back another). Empty when the row is currently locked by another instance of this
     * job or by {@link ApplyBankPaymentUseCase}, or when it no longer matches the due predicate (already confirmed,
     * already cancelled, or its deadline changed) — the caller treats both cases identically: skip it now, and let
     * the next run decide again from scratch.
     */
    Optional<Reservation> claimIfDue(UUID id, Instant now);

    /** How many rows currently match the due predicate — backs the {@code reservation.autocancel.overdue} gauge. */
    long countDue(Instant now);
}
