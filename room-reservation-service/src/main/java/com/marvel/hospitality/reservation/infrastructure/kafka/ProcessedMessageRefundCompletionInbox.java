package com.marvel.hospitality.reservation.infrastructure.kafka;

import com.marvel.hospitality.platform.inbox.ProcessedMessageInbox;
import com.marvel.hospitality.reservation.application.RefundCompletionInbox;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link RefundCompletionInbox} on the platform inbox ({@code processed_message}). The dedupe key of
 * {@code refund-completed} is {@code refundId} (outbox-and-inbox.md).
 *
 * <p>The consumer name is a fixed name of this consumer, deliberately not the Kafka consumer group (see
 * {@link ProcessedMessagePaymentInbox}). Never change {@link #CONSUMER}; stored rows are keyed by it.
 */
@Component
class ProcessedMessageRefundCompletionInbox implements RefundCompletionInbox {

    static final String CONSUMER = "refund-completed";

    private final ProcessedMessageInbox inbox;

    ProcessedMessageRefundCompletionInbox(ProcessedMessageInbox inbox) {
        this.inbox = inbox;
    }

    @Override
    public boolean firstDelivery(UUID refundId) {
        return inbox.markProcessed(refundId.toString(), CONSUMER, RefundCompletedListener.TOPIC);
    }
}
