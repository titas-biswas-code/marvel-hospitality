package com.marvel.hospitality.payment.infrastructure.outbox;

import com.marvel.hospitality.payment.domain.BankTransaction;
import java.math.BigDecimal;

/**
 * The exact {@code bank-transfer-payment-update} value (contracts/events.md), field names verbatim from the brief —
 * including {@code debtorAccountnumber} with a lower-case {@code n}, which is the brief's spelling, not a typo to fix.
 * No {@code propertyId}: the bank does not know one, and matching is the reservation service's job (ADR-0014).
 *
 * <p>{@code amountReceived} is a scale-2 {@code BigDecimal} written in plain notation
 * ({@code spring.jackson.write.write-bigdecimal-as-plain}), so it lands in the jsonb payload as {@code 120.00}; the
 * connector publishes that text unchanged (infra/debezium/payment-outbox.json, no JSON expansion).
 */
public record PaymentReceivedPayload(
        String paymentId,
        String debtorAccountnumber,
        BigDecimal amountReceived,
        String transactionDescription) {

    public static PaymentReceivedPayload from(BankTransaction transaction) {
        return new PaymentReceivedPayload(
                transaction.paymentId().toString(),
                transaction.debtorAccountNumber(),
                transaction.amount(),
                transaction.remittanceInformation());
    }
}
