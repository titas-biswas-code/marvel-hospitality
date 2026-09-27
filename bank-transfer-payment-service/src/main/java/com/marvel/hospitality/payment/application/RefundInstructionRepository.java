package com.marvel.hospitality.payment.application;

import com.marvel.hospitality.payment.domain.RefundInstruction;
import java.util.Optional;
import java.util.UUID;

/** Port to the {@code refund_instruction} table. */
public interface RefundInstructionRepository {

    /**
     * Stores {@code instruction} in its final state (this service's stub executor decides {@code EXECUTED} or
     * {@code FAILED} before persisting anything, so there is only ever one write per refund, never an update).
     */
    void add(RefundInstruction instruction);

    Optional<RefundInstruction> findById(UUID refundId);
}
