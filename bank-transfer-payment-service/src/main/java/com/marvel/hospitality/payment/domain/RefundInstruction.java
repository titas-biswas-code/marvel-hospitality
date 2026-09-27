package com.marvel.hospitality.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A refund the payment service instructs back to the original debtor account for a {@code refund-requested} message
 * (ADR-0014). {@code creditorAccountNumber} is copied from the {@code bank_transaction.debtor_account_number} the
 * refund's {@code paymentId} names, never taken from the request: money always goes back to whoever sent it.
 *
 * <p>A fresh instruction starts {@link RefundInstructionStatus#RECEIVED} ({@link #received}) and transitions exactly
 * once, to {@link RefundInstructionStatus#EXECUTED} ({@link #executed}) or {@link RefundInstructionStatus#FAILED}
 * ({@link #failed}); either method on an instruction that is not still {@code RECEIVED} throws {@link
 * IllegalStateException}. Invariants: {@code amount} is positive with at most two fraction digits (stored at
 * scale 2, never rounded), matching {@link BankTransaction}.
 */
public record RefundInstruction(
        UUID refundId,
        UUID paymentId,
        String reservationId,
        String propertyId,
        String creditorAccountNumber,
        BigDecimal amount,
        String currency,
        RefundReason reason,
        RefundInstructionStatus status,
        @Nullable String failureReason,
        Instant createdAt,
        @Nullable Instant executedAt) {

    public RefundInstruction {
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(paymentId, "paymentId");
        requireNonBlank(reservationId, "reservationId");
        requireNonBlank(propertyId, "propertyId");
        requireNonBlank(creditorAccountNumber, "creditorAccountNumber");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("amount must have at most 2 fraction digits, was " + amount);
        }
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        requireNonBlank(currency, "currency");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** A refund just requested: mints no id (the caller already has one from {@code refund-requested}). */
    public static RefundInstruction received(UUID refundId, UUID paymentId, String reservationId, String propertyId,
            String creditorAccountNumber, BigDecimal amount, String currency, RefundReason reason, Instant createdAt) {
        return new RefundInstruction(refundId, paymentId, reservationId, propertyId, creditorAccountNumber, amount,
                currency, reason, RefundInstructionStatus.RECEIVED, null, createdAt, null);
    }

    /** The payout rail confirmed the transfer. */
    public RefundInstruction executed(Instant executedAt) {
        requireReceived();
        Objects.requireNonNull(executedAt, "executedAt");
        return new RefundInstruction(refundId, paymentId, reservationId, propertyId, creditorAccountNumber, amount,
                currency, reason, RefundInstructionStatus.EXECUTED, null, createdAt, executedAt);
    }

    /**
     * The payout rail rejected the transfer, or a safety check stopped it before the rail was even called.
     * {@code executedAt} stays {@code null}: only an {@link RefundInstructionStatus#EXECUTED} instruction has one
     * (rest-api.md); when it failed travels as {@code completedAt} on the {@code RefundCompleted} event.
     */
    public RefundInstruction failed(String failureReason) {
        requireReceived();
        requireNonBlank(failureReason, "failureReason");
        return new RefundInstruction(refundId, paymentId, reservationId, propertyId, creditorAccountNumber, amount,
                currency, reason, RefundInstructionStatus.FAILED, failureReason, createdAt, null);
    }

    private void requireReceived() {
        if (status != RefundInstructionStatus.RECEIVED) {
            throw new IllegalStateException(
                    "Refund " + refundId + " is " + status + "; only a RECEIVED instruction can transition");
        }
    }

    private static void requireNonBlank(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
