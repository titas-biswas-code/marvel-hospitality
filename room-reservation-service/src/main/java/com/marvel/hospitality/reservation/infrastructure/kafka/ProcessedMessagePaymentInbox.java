package com.marvel.hospitality.reservation.infrastructure.kafka;

import com.marvel.hospitality.platform.inbox.ProcessedMessageInbox;
import com.marvel.hospitality.reservation.application.PaymentInbox;
import org.springframework.stereotype.Component;

/**
 * {@link PaymentInbox} on the platform inbox ({@code processed_message}). The dedupe key of the bank topic is
 * {@code paymentId} (outbox-and-inbox.md).
 *
 * <p>The consumer name is a fixed name of this consumer, deliberately not the Kafka consumer group: a group can be
 * renamed or split (ADR-0008), and the inbox must still recognise every message it has already applied. Never change
 * {@link #CONSUMER}; stored rows are keyed by it.
 */
@Component
class ProcessedMessagePaymentInbox implements PaymentInbox {

    static final String CONSUMER = "bank-transfer-payment-update";

    private final ProcessedMessageInbox inbox;

    ProcessedMessagePaymentInbox(ProcessedMessageInbox inbox) {
        this.inbox = inbox;
    }

    @Override
    public boolean firstDelivery(String paymentId) {
        return inbox.markProcessed(paymentId, CONSUMER, BankTransferPaymentUpdateListener.TOPIC);
    }
}
