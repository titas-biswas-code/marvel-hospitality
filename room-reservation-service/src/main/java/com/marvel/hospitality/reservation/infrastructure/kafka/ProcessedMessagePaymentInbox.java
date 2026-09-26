package com.marvel.hospitality.reservation.infrastructure.kafka;

import com.marvel.hospitality.platform.inbox.ProcessedMessageInbox;
import com.marvel.hospitality.reservation.application.PaymentInbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link PaymentInbox} on the platform inbox ({@code processed_message}). The dedupe key of the bank topic is
 * {@code paymentId} (outbox-and-inbox.md); the consumer name is this service's consumer group.
 */
@Component
class ProcessedMessagePaymentInbox implements PaymentInbox {

    private final ProcessedMessageInbox inbox;
    private final String consumer;

    ProcessedMessagePaymentInbox(ProcessedMessageInbox inbox, @Value("${spring.kafka.consumer.group-id}") String consumer) {
        this.inbox = inbox;
        this.consumer = consumer;
    }

    @Override
    public boolean firstDelivery(String paymentId) {
        return inbox.markProcessed(paymentId, consumer, BankTransferPaymentUpdateListener.TOPIC);
    }
}
