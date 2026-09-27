package com.marvel.hospitality.payment.infrastructure.outbox;

import com.marvel.hospitality.payment.application.PaymentEventOutbox;
import com.marvel.hospitality.payment.application.RefundCompletion;
import com.marvel.hospitality.payment.domain.BankTransaction;
import com.marvel.hospitality.platform.outbox.OutboxEventWriter;
import com.marvel.hospitality.platform.outbox.OutboxMessage;
import org.springframework.stereotype.Component;

/**
 * {@link PaymentEventOutbox} on the platform {@link OutboxEventWriter}: one {@code outbox_event} row in the caller's
 * transaction, published by Debezium (ADR-0006/0007). {@code property_id} is {@code null} on {@code PaymentReceived}
 * because the externally defined bank topic carries none (so the {@code propertyId} header is present with a null value,
 * contracts/events.md); {@code RefundCompleted} does carry one, taken from the refund request.
 */
@Component
class PaymentOutboxWriter implements PaymentEventOutbox {

    static final String PAYMENT_AGGREGATE_TYPE = "payment";
    static final String PAYMENT_RECEIVED_EVENT_TYPE = "PaymentReceived";
    static final String PAYMENT_TOPIC = "bank-transfer-payment-update";

    static final String REFUND_AGGREGATE_TYPE = "refund";
    static final String REFUND_COMPLETED_EVENT_TYPE = "RefundCompleted";
    static final String REFUND_TOPIC = "refund-completed";

    private final OutboxEventWriter outboxEventWriter;

    PaymentOutboxWriter(OutboxEventWriter outboxEventWriter) {
        this.outboxEventWriter = outboxEventWriter;
    }

    @Override
    public void paymentReceived(BankTransaction transaction) {
        outboxEventWriter.append(new OutboxMessage(PAYMENT_AGGREGATE_TYPE, transaction.paymentId().toString(),
                PAYMENT_RECEIVED_EVENT_TYPE, PAYMENT_TOPIC, null, PaymentReceivedPayload.from(transaction)));
    }

    @Override
    public void refundCompleted(RefundCompletion completion) {
        // Key = paymentId (contracts/events.md): every refund event about one payment lands on one partition, in order.
        outboxEventWriter.append(new OutboxMessage(REFUND_AGGREGATE_TYPE, completion.paymentId().toString(),
                REFUND_COMPLETED_EVENT_TYPE, REFUND_TOPIC, completion.propertyId(), RefundCompletedPayload.from(completion)));
    }
}
