package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.Refund;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@code refund}. Implementations run inside the caller's transaction. */
public interface RefundRepository {

    void add(Refund refund);

    Optional<Refund> findById(UUID refundId);

    /** Writes the refund's outcome ({@code status}, {@code failure_reason}, {@code completed_at}). */
    void update(Refund refund);

    /** The refunds of the given payments, in no particular order; at most one per payment. */
    List<Refund> findByPaymentIds(Collection<String> paymentIds);
}
