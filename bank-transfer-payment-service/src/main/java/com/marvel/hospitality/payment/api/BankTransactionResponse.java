package com.marvel.hospitality.payment.api;

import com.marvel.hospitality.payment.domain.BankTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The {@code GET /bank-transactions/{paymentId}} body (rest-api.md). */
public record BankTransactionResponse(
        String paymentId,
        String bankTransactionRef,
        String debtorAccountNumber,
        @Nullable String debtorName,
        BigDecimal amount,
        String currency,
        String remittanceInformation,
        Instant bookedAt,
        Instant receivedAt) {

    static BankTransactionResponse from(BankTransaction transaction) {
        return new BankTransactionResponse(transaction.paymentId().toString(), transaction.bankTransactionRef(),
                transaction.debtorAccountNumber(), transaction.debtorName(), transaction.amount(),
                transaction.currency(), transaction.remittanceInformation(), transaction.bookedAt(),
                transaction.receivedAt());
    }
}
