package com.marvel.hospitality.payment.application;

import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A bank transaction as the bank posted it.
 *
 * @param raw the request body as JSON, stored verbatim in {@code bank_transaction.raw}
 */
public record IngestBankTransactionCommand(
        String bankTransactionRef,
        String debtorAccountNumber,
        @Nullable String debtorName,
        BigDecimal amount,
        String currency,
        String remittanceInformation,
        Instant bookedAt,
        String raw) {
}
