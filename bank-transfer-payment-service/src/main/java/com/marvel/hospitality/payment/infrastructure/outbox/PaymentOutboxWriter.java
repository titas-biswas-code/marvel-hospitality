package com.marvel.hospitality.payment.infrastructure.outbox;

import com.marvel.hospitality.payment.application.PaymentEventOutbox;
import com.marvel.hospitality.payment.domain.BankTransaction;
import com.marvel.hospitality.platform.outbox.OutboxEventWriter;
import com.marvel.hospitality.platform.outbox.OutboxMessage;
import org.springframework.stereotype.Component;

/**
 * {@link PaymentEventOutbox} on the platform {@link OutboxEventWriter}: one {@code outbox_event} row in the caller's
 * transaction, published by Debezium (ADR-0006/0007). {@code property_id} is {@code null} because the brief's bank
 * topic carries none (so the {@code propertyId} header is present with a null value, contracts/events.md).
 */
@Component
class PaymentOutboxWriter implements PaymentEventOutbox {

    static final String AGGREGATE_TYPE = "payment";
    static final String EVENT_TYPE = "PaymentReceived";
    static final String TOPIC = "bank-transfer-payment-update";

    private final OutboxEventWriter outboxEventWriter;

    PaymentOutboxWriter(OutboxEventWriter outboxEventWriter) {
        this.outboxEventWriter = outboxEventWriter;
    }

    @Override
    public void paymentReceived(BankTransaction transaction) {
        outboxEventWriter.append(new OutboxMessage(AGGREGATE_TYPE, transaction.paymentId().toString(), EVENT_TYPE,
                TOPIC, null, PaymentReceivedPayload.from(transaction)));
    }
}
