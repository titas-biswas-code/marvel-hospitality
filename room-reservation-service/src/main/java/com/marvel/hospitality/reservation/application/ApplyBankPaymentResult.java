package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.PaymentMatchOutcome;
import com.marvel.hospitality.reservation.domain.RefundReason;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * @param outcome how the payment was classified; {@code null} for a duplicate delivery, which changed nothing
 * @param refundRequested why a refund was requested along with the payment, or {@code null} when none was
 */
public record ApplyBankPaymentResult(@Nullable PaymentMatchOutcome outcome, @Nullable RefundReason refundRequested) {

    static ApplyBankPaymentResult duplicate() {
        return new ApplyBankPaymentResult(null, null);
    }

    static ApplyBankPaymentResult applied(PaymentMatchOutcome outcome) {
        return applied(outcome, null);
    }

    static ApplyBankPaymentResult applied(PaymentMatchOutcome outcome, @Nullable RefundReason refundRequested) {
        return new ApplyBankPaymentResult(Objects.requireNonNull(outcome, "outcome"), refundRequested);
    }

    public boolean isDuplicate() {
        return outcome == null;
    }
}
