package com.marvel.hospitality.payment.api;

import com.marvel.hospitality.payment.application.IngestBankTransactionCommand;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The {@code POST /bank-transactions} body (rest-api.md). Shape rules answer {@code 400 VALIDATION_FAILED}: sizes
 * follow the {@code bank_transaction} columns, and {@code amount <= 0} is a 400 by contract. A well-formed currency
 * other than EUR is a business rule, not a shape error, so it is left to the domain ({@code 422 UNSUPPORTED_CURRENCY}).
 */
public record IngestBankTransactionRequest(
        @NotBlank @Size(max = 64) String bankTransactionRef,
        @NotBlank @Size(max = 34) String debtorAccountNumber,
        @Size(max = 140) @Nullable String debtorName,
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code") String currency,
        @NotBlank @Size(max = 255) String remittanceInformation,
        @NotNull Instant bookedAt) {

    IngestBankTransactionCommand toCommand(String raw) {
        return new IngestBankTransactionCommand(bankTransactionRef, debtorAccountNumber, debtorName, amount, currency,
                remittanceInformation, bookedAt, raw);
    }
}
