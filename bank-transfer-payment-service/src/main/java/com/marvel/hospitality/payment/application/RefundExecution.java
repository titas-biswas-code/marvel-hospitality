package com.marvel.hospitality.payment.application;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The payout rail's answer for one {@link com.marvel.hospitality.payment.domain.RefundInstruction}.
 *
 * @param successful whether the rail accepted the transfer
 * @param failureReason {@code null} when {@code successful}; otherwise the reason recorded on the instruction and on
 *        the {@code RefundCompleted} event
 */
public record RefundExecution(boolean successful, @Nullable String failureReason) {

    public RefundExecution {
        if (successful && failureReason != null) {
            throw new IllegalArgumentException("a successful execution must not carry a failureReason");
        }
        if (!successful && (failureReason == null || failureReason.isBlank())) {
            throw new IllegalArgumentException("a failed execution must carry a non-blank failureReason");
        }
    }

    public static RefundExecution success() {
        return new RefundExecution(true, null);
    }

    public static RefundExecution failure(String failureReason) {
        return new RefundExecution(false, Objects.requireNonNull(failureReason, "failureReason"));
    }
}
