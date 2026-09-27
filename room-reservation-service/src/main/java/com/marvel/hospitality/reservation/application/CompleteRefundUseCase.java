package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.Refund;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Records the payment service's answer to a refund request (saga step 6 of ADR-0006) in a single local transaction:
 * inbox on {@code refundId}, then the refund becomes {@code COMPLETED} or {@code FAILED}.
 *
 * <p>A failed refund is money the hotel still owes and no automation will retry it (a real payout rail would have
 * said why, and a blind retry could pay twice), so it is logged at ERROR with every id a human needs to act on.
 *
 * <p>An answer that does not fit the request — unknown {@code refundId}, another {@code paymentId} or amount, or a
 * refund that already has an outcome under a different message — is a contract violation, not a business outcome:
 * it throws {@link IllegalArgumentException}, which the Kafka error handler dead-letters without retries (ADR-0008),
 * and rolls the inbox row back with everything else.
 */
public class CompleteRefundUseCase {

    private static final Logger log = LoggerFactory.getLogger(CompleteRefundUseCase.class);

    private final RefundCompletionInbox inbox;
    private final RefundRepository refunds;
    private final TransactionOperations transactions;

    public CompleteRefundUseCase(RefundCompletionInbox inbox, RefundRepository refunds,
            TransactionOperations transactions) {
        this.inbox = inbox;
        this.refunds = refunds;
        this.transactions = transactions;
    }

    public CompleteRefundResult complete(CompleteRefundCommand command) {
        Objects.requireNonNull(command, "command");
        return Objects.requireNonNull(transactions.execute(status -> completeInTransaction(command)));
    }

    private CompleteRefundResult completeInTransaction(CompleteRefundCommand command) {
        if (!inbox.firstDelivery(command.refundId())) {
            log.debug("Refund {} outcome already recorded; duplicate delivery skipped", command.refundId());
            return CompleteRefundResult.duplicate();
        }
        Refund refund = refunds.findById(command.refundId()).orElseThrow(() -> new IllegalArgumentException(
                "refund-completed for unknown refund " + command.refundId()));
        try (MDC.MDCCloseable reservation = MDC.putCloseable("reservationId", refund.reservationId().value());
                MDC.MDCCloseable property = MDC.putCloseable("propertyId", refund.propertyId())) {
            requireMatches(refund, command);
            if (command.completed()) {
                refund.complete(command.completedAt());
                log.info("Refund {} of {} {} for payment {} of reservation {} completed", refund.refundId(),
                        refund.amount().amount().toPlainString(), refund.amount().currency(), refund.paymentId(),
                        refund.reservationId());
            } else {
                refund.fail(Objects.requireNonNull(command.failureReason()), command.completedAt());
                log.error("Refund {} of {} {} for payment {} of reservation {} (property {}) FAILED: {}; "
                                + "the money is still owed and needs manual follow-up",
                        refund.refundId(), refund.amount().amount().toPlainString(), refund.amount().currency(),
                        refund.paymentId(), refund.reservationId(), refund.propertyId(), refund.failureReason());
            }
            refunds.update(refund);
            return CompleteRefundResult.recorded(refund.status());
        }
    }

    private static void requireMatches(Refund refund, CompleteRefundCommand command) {
        if (!refund.paymentId().equals(command.paymentId())) {
            throw new IllegalArgumentException("refund-completed for refund " + refund.refundId() + " names payment "
                    + command.paymentId() + ", but the refund belongs to payment " + refund.paymentId());
        }
        if (refund.amount().compareTo(command.amount()) != 0) {
            throw new IllegalArgumentException("refund-completed for refund " + refund.refundId() + " reports "
                    + command.amount().amount() + ", but " + refund.amount().amount() + " was requested");
        }
        if (refund.completedAt() != null) {
            throw new IllegalArgumentException(
                    "refund " + refund.refundId() + " already has an outcome: " + refund.status());
        }
    }
}
