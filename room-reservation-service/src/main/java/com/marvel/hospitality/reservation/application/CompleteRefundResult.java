package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.RefundStatus;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * @param status the refund's final status; {@code null} for a duplicate delivery, which changed nothing
 */
public record CompleteRefundResult(@Nullable RefundStatus status) {

    static CompleteRefundResult duplicate() {
        return new CompleteRefundResult(null);
    }

    static CompleteRefundResult recorded(RefundStatus status) {
        return new CompleteRefundResult(Objects.requireNonNull(status, "status"));
    }

    public boolean isDuplicate() {
        return status == null;
    }
}
