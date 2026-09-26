package com.marvel.hospitality.reservation.infrastructure.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.marvel.hospitality.reservation.application.ApplyBankPaymentCommand;
import com.marvel.hospitality.reservation.domain.Money;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * The value of {@code bank-transfer-payment-update} (events.md), field names as the brief defines them, including
 * {@code debtorAccountnumber} with a lower-case {@code n}. A value that breaks these constraints is a contract
 * violation: it goes to the DLT without retries (ADR-0008). An unreadable {@code transactionDescription} is not one;
 * it is a business outcome ({@code UNMATCHED_FORMAT}), so the description only has to be present.
 */
record BankTransferPaymentUpdateMessage(
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        String paymentId,
        @JsonProperty("debtorAccountnumber") @NotBlank @Size(max = 34) String debtorAccountNumber,
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amountReceived,
        @NotNull @Size(max = 255) String transactionDescription) {

    ApplyBankPaymentCommand toCommand() {
        return new ApplyBankPaymentCommand(
                paymentId, debtorAccountNumber, Money.eur(amountReceived), transactionDescription);
    }
}
