package com.marvel.hospitality.reservation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A monetary amount, domain-internal only: the API and event contracts (rest-api.md, events.md) keep flat
 * {@code amount} + {@code currency} fields rather than a nested object, so mapping to/from those shapes
 * happens at the edges, not here. Single-currency assignment (EUR only) is enforced by this compact
 * constructor rather than modelled with a currency-aware money library; JavaMoney/Moneta were considered and
 * rejected as disproportionate for one currency (ADR-0016).
 *
 * <p>Amounts are normalised to exactly two fraction digits with {@link RoundingMode#UNNECESSARY}: a caller
 * that passes a third decimal digit gets an {@link IllegalArgumentException} rather than a silently rounded
 * amount — silently changing a money value is worse than failing loudly.
 */
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {

    public static final String EUR = "EUR";

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (!EUR.equals(currency)) {
            throw new IllegalArgumentException("Only EUR is supported in this assignment, got: " + currency);
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        }
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("amount must have at most 2 fraction digits: " + amount, e);
        }
    }

    public static Money eur(BigDecimal amount) {
        return new Money(amount, EUR);
    }

    public static Money eur(String amount) {
        return new Money(new BigDecimal(amount), EUR);
    }

    public static Money zeroEur() {
        return new Money(BigDecimal.ZERO, EUR);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money times(int nights) {
        return new Money(amount.multiply(BigDecimal.valueOf(nights)), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
