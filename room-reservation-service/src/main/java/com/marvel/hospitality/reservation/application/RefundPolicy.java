package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import com.marvel.hospitality.reservation.domain.Refund;
import com.marvel.hospitality.reservation.domain.RefundDue;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What happens when money has to go back (ADR-0009: an overpayment's surplus, or a payment for a reservation that is
 * no longer awaiting payment): a {@code refund} row in state {@code REQUESTED} and its {@code RefundRequested} outbox
 * row, which Debezium publishes to {@code refund-requested} for the payment service to pay out (ADR-0006, step 3).
 *
 * <p>Called by {@link ApplyBankPaymentUseCase} inside the payment's transaction and never opens one of its own, so the
 * payment, the refund and the event commit or roll back together: a payment is never recorded without the refund it
 * makes due, and a refund is never requested for a payment that was rolled back.
 */
public class RefundPolicy {

    private static final Logger log = LoggerFactory.getLogger(RefundPolicy.class);

    private final RefundRepository refunds;
    private final OutboxWriter outbox;
    private final Clock clock;

    public RefundPolicy(RefundRepository refunds, OutboxWriter outbox, Clock clock) {
        this.refunds = refunds;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * @param payment the payment the refund belongs to; always linked to a reservation (unmatched payments are never
     *        refunded automatically, ADR-0009)
     */
    public void refundDue(ReceivedPayment payment, RefundDue due) {
        Refund refund = Refund.request(UUID.randomUUID(), payment, due, Instant.now(clock));
        refunds.add(refund);
        refund.pullEvents().forEach(outbox::append);
        log.info("Refund {} requested for payment {} of reservation {}: {} {} ({})", refund.refundId(),
                payment.paymentId(), refund.reservationId(), refund.amount().amount().toPlainString(),
                refund.amount().currency(), refund.reason());
    }
}
