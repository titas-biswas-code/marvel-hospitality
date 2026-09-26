package com.marvel.hospitality.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plain-Java invariants of {@link BankTransaction} (no Spring anywhere in this test, ADR-0001's domain rule). */
class BankTransactionTest {

    private static final Instant BOOKED_AT = Instant.parse("2026-10-01T09:15:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-10-01T09:15:02Z");
    private static final String IBAN = "NL91ABNA0417164300";

    @Test
    void normalisesAWholeEuroAmountToScaleTwo() {
        BankTransaction transaction = BankTransaction.receive(
                "BANK-TX-1", IBAN, "A. Lovelace", new BigDecimal("120"), "EUR", "some remittance info",
                BOOKED_AT, RECEIVED_AT);

        assertThat(transaction.amount()).isEqualByComparingTo("120.00");
        assertThat(transaction.amount().scale()).isEqualTo(2);
    }

    @Test
    void rejectsZeroAmount() {
        assertThatThrownBy(() -> BankTransaction.receive(
                "BANK-TX-2", IBAN, null, BigDecimal.ZERO, "EUR", "info", BOOKED_AT, RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> BankTransaction.receive(
                "BANK-TX-3", IBAN, null, new BigDecimal("-5.00"), "EUR", "info", BOOKED_AT, RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAmountWithMoreThanTwoFractionDigits() {
        assertThatThrownBy(() -> BankTransaction.receive(
                "BANK-TX-4", IBAN, null, new BigDecimal("1.005"), "EUR", "info", BOOKED_AT, RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnUnsupportedCurrency() {
        assertThatThrownBy(() -> BankTransaction.receive(
                "BANK-TX-5", IBAN, null, new BigDecimal("50.00"), "USD", "info", BOOKED_AT, RECEIVED_AT))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .extracting(ex -> ((UnsupportedCurrencyException) ex).currency())
                .isEqualTo("USD");
    }

    @Test
    void describesSameTransferAsIsTrueForSameBankFieldsWithDifferentPaymentIdAndReceivedAt() {
        BankTransaction first = BankTransaction.receive(
                "BANK-TX-6", IBAN, "A. Lovelace", new BigDecimal("120.00"), "EUR", "info", BOOKED_AT, RECEIVED_AT);
        BankTransaction repeated = BankTransaction.receive(
                "BANK-TX-6", IBAN, "A. Lovelace", new BigDecimal("120.00"), "EUR", "info", BOOKED_AT,
                Instant.parse("2026-10-01T09:20:00Z"));

        assertThat(first.describesSameTransferAs(repeated)).isTrue();
        assertThat(first.paymentId()).isNotEqualTo(repeated.paymentId());
    }

    @Test
    void describesSameTransferAsIsFalseWhenAmountDiffers() {
        BankTransaction first = BankTransaction.receive(
                "BANK-TX-7", IBAN, "A. Lovelace", new BigDecimal("120.00"), "EUR", "info", BOOKED_AT, RECEIVED_AT);
        BankTransaction differentAmount = BankTransaction.receive(
                "BANK-TX-7", IBAN, "A. Lovelace", new BigDecimal("50.00"), "EUR", "info", BOOKED_AT, RECEIVED_AT);

        assertThat(first.describesSameTransferAs(differentAmount)).isFalse();
    }

    @Test
    void receiveMintsADistinctPaymentIdEachTime() {
        BankTransaction first = BankTransaction.receive(
                "BANK-TX-8", IBAN, null, new BigDecimal("10.00"), "EUR", "info", BOOKED_AT, RECEIVED_AT);
        BankTransaction second = BankTransaction.receive(
                "BANK-TX-8", IBAN, null, new BigDecimal("10.00"), "EUR", "info", BOOKED_AT, RECEIVED_AT);

        assertThat(first.paymentId()).isInstanceOf(UUID.class);
        assertThat(second.paymentId()).isInstanceOf(UUID.class);
        assertThat(first.paymentId()).isNotEqualTo(second.paymentId());
    }
}
