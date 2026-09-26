package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.PaymentMatchOutcome;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * @param outcome how the payment was classified; {@code null} for a duplicate delivery, which changed nothing
 */
public record ApplyBankPaymentResult(@Nullable PaymentMatchOutcome outcome) {

    static ApplyBankPaymentResult duplicate() {
        return new ApplyBankPaymentResult(null);
    }

    static ApplyBankPaymentResult applied(PaymentMatchOutcome outcome) {
        return new ApplyBankPaymentResult(Objects.requireNonNull(outcome, "outcome"));
    }

    public boolean isDuplicate() {
        return outcome == null;
    }
}
