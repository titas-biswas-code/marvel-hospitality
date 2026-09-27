package com.marvel.hospitality.payment.application;

import com.marvel.hospitality.payment.domain.RefundInstructionStatus;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * @param outcome the instruction's final status ({@code EXECUTED} or {@code FAILED}); {@code null} for a duplicate
 *        delivery, which changed nothing
 */
public record ExecuteRefundResult(@Nullable RefundInstructionStatus outcome) {

    static ExecuteRefundResult duplicate() {
        return new ExecuteRefundResult(null);
    }

    static ExecuteRefundResult executed(RefundInstructionStatus outcome) {
        return new ExecuteRefundResult(Objects.requireNonNull(outcome, "outcome"));
    }

    public boolean isDuplicate() {
        return outcome == null;
    }
}
