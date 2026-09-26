package com.marvel.hospitality.reservation.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The result of {@link PaymentMatcher} classifying one incoming bank payment against a reservation
 * (ADR-0009's outcome table). {@code amountReceived} is the reservation's new <em>total</em> received (not the
 * amount of this one payment) so a caller never has to re-derive it; it is present for exactly the three
 * outcomes that represent money actually landing on a pending reservation. {@code refund} is present for
 * exactly the outcomes ADR-0009 says trigger a refund: an overpaid surplus, or a payment that arrived for a
 * reservation that is no longer waiting for one.
 */
public record PaymentMatch(PaymentMatchOutcome outcome, @Nullable Money amountReceived, @Nullable RefundDue refund) {

    public PaymentMatch {
        Objects.requireNonNull(outcome, "outcome");
        boolean amountExpected = isMatched(outcome);
        if (amountExpected && amountReceived == null) {
            throw new IllegalArgumentException("amountReceived is required for outcome " + outcome);
        }
        if (!amountExpected && amountReceived != null) {
            throw new IllegalArgumentException("amountReceived must be null for outcome " + outcome);
        }
        boolean refundExpected = outcome == PaymentMatchOutcome.OVERPAID || outcome == PaymentMatchOutcome.UNMATCHED_NOT_PENDING;
        if (refundExpected && refund == null) {
            throw new IllegalArgumentException("refund is required for outcome " + outcome);
        }
        if (!refundExpected && refund != null) {
            throw new IllegalArgumentException("refund must be null for outcome " + outcome);
        }
    }

    /** {@code true} for the three outcomes where the payment actually counted towards the reservation. */
    public boolean matched() {
        return isMatched(outcome);
    }

    private static boolean isMatched(PaymentMatchOutcome outcome) {
        return outcome == PaymentMatchOutcome.MATCHED_PARTIAL
                || outcome == PaymentMatchOutcome.MATCHED_FULL
                || outcome == PaymentMatchOutcome.OVERPAID;
    }
}
