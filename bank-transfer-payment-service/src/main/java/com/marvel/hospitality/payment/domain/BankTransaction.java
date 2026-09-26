package com.marvel.hospitality.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One incoming bank transfer as the bank reported it, plus the {@code paymentId} Marvel mints for it (ADR-0014,
 * contracts/identifiers.md). The ledger never interprets {@code remittanceInformation}: matching it to a
 * reservation is the reservation service's business (ADR-0009).
 *
 * <p>Invariants: {@code amount} is positive with at most two fraction digits (stored at scale 2, never rounded:
 * more digits is a bug upstream), and {@code currency} is the one supported currency.
 */
public record BankTransaction(
        UUID paymentId,
        String bankTransactionRef,
        String debtorAccountNumber,
        @Nullable String debtorName,
        BigDecimal amount,
        String currency,
        String remittanceInformation,
        Instant bookedAt,
        Instant receivedAt) {

    /** The only currency this assignment supports (ADR-0016). */
    public static final String SUPPORTED_CURRENCY = "EUR";

    public BankTransaction {
        Objects.requireNonNull(paymentId, "paymentId");
        requireNonBlank(bankTransactionRef, "bankTransactionRef");
        requireNonBlank(debtorAccountNumber, "debtorAccountNumber");
        requireNonBlank(remittanceInformation, "remittanceInformation");
        Objects.requireNonNull(bookedAt, "bookedAt");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(currency, "currency");
        if (!SUPPORTED_CURRENCY.equals(currency)) {
            throw new UnsupportedCurrencyException(currency);
        }
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("amount must have at most 2 fraction digits, was " + amount);
        }
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Records a transaction the bank has just reported: mints its {@code paymentId} and stamps {@code receivedAt}. */
    public static BankTransaction receive(
            String bankTransactionRef, String debtorAccountNumber, @Nullable String debtorName, BigDecimal amount,
            String currency, String remittanceInformation, Instant bookedAt, Instant receivedAt) {
        return new BankTransaction(UUID.randomUUID(), bankTransactionRef, debtorAccountNumber, debtorName, amount,
                currency, remittanceInformation, bookedAt, receivedAt);
    }

    /**
     * Whether {@code other} reports the same transfer: equal in everything the bank said, ignoring what Marvel
     * assigned ({@code paymentId}, {@code receivedAt}).
     */
    public boolean describesSameTransferAs(BankTransaction other) {
        return bankTransactionRef.equals(other.bankTransactionRef)
                && debtorAccountNumber.equals(other.debtorAccountNumber)
                && Objects.equals(debtorName, other.debtorName)
                && amount.compareTo(other.amount) == 0
                && currency.equals(other.currency)
                && remittanceInformation.equals(other.remittanceInformation)
                && bookedAt.equals(other.bookedAt);
    }

    private static void requireNonBlank(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
