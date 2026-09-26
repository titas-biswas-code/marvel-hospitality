package com.marvel.hospitality.reservation.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Implements ADR-0009's matching rules. Stateless (no fields but the compiled pattern), so it is registered as
 * a singleton Spring bean by the application layer rather than constructed per call; kept in {@code domain}
 * because the matching rules are business logic, not infrastructure.
 *
 * <p>Matching is deliberately exact: no case folding, no whitespace tolerance beyond the single {@code trim()}
 * identifiers.md already allows, no fuzzy reservation-id lookup. A wrong match confirms the wrong room, which
 * is worse than leaving a payment unmatched for a human to reconcile (ADR-0009, "alternatives considered").
 */
public final class PaymentMatcher {

    /** Anchored, exactly as specified in identifiers.md — do not relax without updating that contract. */
    private static final Pattern REMITTANCE_PATTERN = Pattern.compile("^(\\S{10}) (P[0-9A-HJKMNP-TV-Z]{7})$");

    public PaymentMatcher() {
    }

    /**
     * Parses a bank {@code transactionDescription} into a {@link Remittance}. Only {@code trim()} is applied
     * before matching — no other normalisation, per ADR-0009. Returns empty for {@code null} or anything that
     * does not match the anchored regex; the caller ({@link #unmatchedFormat()}) turns that into the
     * corresponding outcome.
     */
    public Optional<Remittance> parse(@Nullable String transactionDescription) {
        if (transactionDescription == null) {
            return Optional.empty();
        }
        Matcher matcher = REMITTANCE_PATTERN.matcher(transactionDescription.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Remittance(matcher.group(1), ReservationId.of(matcher.group(2))));
    }

    /** The outcome for a {@code transactionDescription} that {@link #parse} could not read at all. */
    public PaymentMatch unmatchedFormat() {
        return new PaymentMatch(PaymentMatchOutcome.UNMATCHED_FORMAT, null, null);
    }

    /**
     * Classifies one incoming payment against the reservation the remittance named (ADR-0009's table).
     *
     * @param reservation the reservation looked up by {@link ReservationId}, or {@code null} if none exists
     * @param previouslyReceived the sum of this reservation's earlier <em>matched</em> payments — the source of
     *     truth per ADR-0009 §"Consequences", not {@link Reservation#amountReceived()}'s denormalised value
     * @param amount this payment's amount; must be positive
     */
    public PaymentMatch classify(@Nullable Reservation reservation, Money previouslyReceived, Money amount) {
        Objects.requireNonNull(previouslyReceived, "previouslyReceived");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        if (reservation == null) {
            return new PaymentMatch(PaymentMatchOutcome.UNMATCHED_UNKNOWN_RESERVATION, null, null);
        }
        return switch (reservation.status()) {
            case CANCELLED -> new PaymentMatch(PaymentMatchOutcome.UNMATCHED_NOT_PENDING, null,
                    new RefundDue(amount, RefundReason.RESERVATION_CANCELLED));
            case CONFIRMED -> new PaymentMatch(PaymentMatchOutcome.UNMATCHED_NOT_PENDING, null,
                    new RefundDue(amount, RefundReason.OVERPAYMENT));
            case PENDING_PAYMENT -> classifyPending(reservation, previouslyReceived, amount);
        };
    }

    private PaymentMatch classifyPending(Reservation reservation, Money previouslyReceived, Money amount) {
        Money newTotal = previouslyReceived.plus(amount);
        int comparedToTotal = newTotal.compareTo(reservation.totalAmount());
        if (comparedToTotal < 0) {
            return new PaymentMatch(PaymentMatchOutcome.MATCHED_PARTIAL, newTotal, null);
        }
        if (comparedToTotal == 0) {
            return new PaymentMatch(PaymentMatchOutcome.MATCHED_FULL, newTotal, null);
        }
        Money surplus = newTotal.minus(reservation.totalAmount());
        return new PaymentMatch(PaymentMatchOutcome.OVERPAID, newTotal, new RefundDue(surplus, RefundReason.OVERPAYMENT));
    }
}
