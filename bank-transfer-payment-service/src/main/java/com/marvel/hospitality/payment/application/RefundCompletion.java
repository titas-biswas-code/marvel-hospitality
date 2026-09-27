package com.marvel.hospitality.payment.application;

import com.marvel.hospitality.payment.domain.RefundInstruction;
import com.marvel.hospitality.payment.domain.RefundInstructionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The {@code RefundCompleted} event's business fields (contracts/events.md), independent of the outbox's wire shape.
 *
 * @param completed {@code true} for {@code status: COMPLETED}, {@code false} for {@code status: FAILED}
 * @param failureReason set only when {@code !completed}
 */
public record RefundCompletion(
        UUID refundId, UUID paymentId, String reservationId, String propertyId, BigDecimal amount, String currency,
        boolean completed, @Nullable String failureReason, Instant completedAt) {

    /** A refund instruction reached its final state ({@code EXECUTED} or {@code FAILED}). */
    public static RefundCompletion from(RefundInstruction instruction, Instant completedAt) {
        boolean completed = instruction.status() == RefundInstructionStatus.EXECUTED;
        return new RefundCompletion(instruction.refundId(), instruction.paymentId(), instruction.reservationId(),
                instruction.propertyId(), instruction.amount(), instruction.currency(), completed,
                completed ? null : instruction.failureReason(), completedAt);
    }

    /** No instruction could be created: {@code paymentId} names no {@code bank_transaction} (ADR-0014). */
    public static RefundCompletion unknownPayment(ExecuteRefundCommand command, String failureReason, Instant completedAt) {
        return new RefundCompletion(command.refundId(), command.paymentId(), command.reservationId(),
                command.propertyId(), command.amount(), command.currency(), false, failureReason, completedAt);
    }
}
