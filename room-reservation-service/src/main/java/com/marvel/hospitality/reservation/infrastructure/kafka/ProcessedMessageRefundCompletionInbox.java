package com.marvel.hospitality.reservation.infrastructure.kafka;

import com.marvel.hospitality.platform.inbox.ProcessedMessageInbox;
import com.marvel.hospitality.reservation.application.RefundCompletionInbox;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link RefundCompletionInbox} on the platform inbox ({@code processed_message}). The dedupe key of
 * {@code refund-completed} is {@code refundId} (outbox-and-inbox.md); the consumer name is this service's consumer
 * group, as for the bank topic (payment and refund ids are both UUIDs, so they never collide).
 */
@Component
class ProcessedMessageRefundCompletionInbox implements RefundCompletionInbox {

    private final ProcessedMessageInbox inbox;
    private final String consumer;

    ProcessedMessageRefundCompletionInbox(ProcessedMessageInbox inbox,
            @Value("${spring.kafka.consumer.group-id}") String consumer) {
        this.inbox = inbox;
        this.consumer = consumer;
    }

    @Override
    public boolean firstDelivery(UUID refundId) {
        return inbox.markProcessed(refundId.toString(), consumer, RefundCompletedListener.TOPIC);
    }
}
