package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.Money;
import java.util.Objects;

/**
 * One {@code bank-transfer-payment-update} message (events.md), already validated at the edge.
 *
 * @param transactionDescription the remittance as the bank delivered it; parsing it is the matcher's job
 */
public record ApplyBankPaymentCommand(
        String paymentId, String debtorAccountNumber, Money amount, String transactionDescription) {

    public ApplyBankPaymentCommand {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(debtorAccountNumber, "debtorAccountNumber");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(transactionDescription, "transactionDescription");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
    }
}
