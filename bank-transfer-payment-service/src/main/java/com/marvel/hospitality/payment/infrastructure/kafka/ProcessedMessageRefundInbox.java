package com.marvel.hospitality.payment.infrastructure.kafka;

import com.marvel.hospitality.payment.application.RefundInbox;
import com.marvel.hospitality.platform.inbox.ProcessedMessageInbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link RefundInbox} on the platform inbox ({@code processed_message}). The dedupe key of {@code refund-requested}
 * is {@code refundId} (outbox-and-inbox.md); the consumer name is this service's consumer group.
 */
@Component
class ProcessedMessageRefundInbox implements RefundInbox {

    private final ProcessedMessageInbox inbox;
    private final String consumer;

    ProcessedMessageRefundInbox(ProcessedMessageInbox inbox, @Value("${spring.kafka.consumer.group-id}") String consumer) {
        this.inbox = inbox;
        this.consumer = consumer;
    }

    @Override
    public boolean firstDelivery(String refundId) {
        return inbox.markProcessed(refundId, consumer, RefundRequestedListener.TOPIC);
    }
}
