package com.marvel.hospitality.reservation.infrastructure.refund;

import com.marvel.hospitality.reservation.application.RefundPolicy;
import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import com.marvel.hospitality.reservation.domain.RefundDue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder {@link RefundPolicy}: logs the refund that is due and does nothing else. The refund saga
 * ({@code refund} row + {@code RefundRequested} outbox event, ADR-0006) replaces it; until then the log line and the
 * payment's {@code received_payment} row are the record that money has to go back.
 */
@Component
class LoggingRefundPolicy implements RefundPolicy {

    private static final Logger log = LoggerFactory.getLogger(LoggingRefundPolicy.class);

    @Override
    public void refundDue(ReceivedPayment payment, RefundDue refund) {
        log.info("Refund due for payment {} of reservation {}: {} {} ({}); refund requests are not emitted yet",
                payment.paymentId(), payment.reservationId(), refund.amount().amount(), refund.amount().currency(),
                refund.reason());
    }
}
