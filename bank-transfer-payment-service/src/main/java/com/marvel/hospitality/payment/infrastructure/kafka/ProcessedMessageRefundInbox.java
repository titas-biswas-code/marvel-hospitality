package com.marvel.hospitality.payment.infrastructure.kafka;

import com.marvel.hospitality.payment.application.RefundInbox;
import com.marvel.hospitality.platform.inbox.ProcessedMessageInbox;
import org.springframework.stereotype.Component;

/**
 * {@link RefundInbox} on the platform inbox ({@code processed_message}). The dedupe key of {@code refund-requested}
 * is {@code refundId} (outbox-and-inbox.md).
 *
 * <p>The consumer name is a fixed name of this consumer, deliberately not the Kafka consumer group: a group can be
 * renamed or split (ADR-0008), and the inbox must still recognise every message it has already applied. Never change
 * {@link #CONSUMER}; stored rows are keyed by it.
 */
@Component
class ProcessedMessageRefundInbox implements RefundInbox {

    static final String CONSUMER = "refund-requested";

    private final ProcessedMessageInbox inbox;

    ProcessedMessageRefundInbox(ProcessedMessageInbox inbox) {
        this.inbox = inbox;
    }

    @Override
    public boolean firstDelivery(String refundId) {
        return inbox.markProcessed(refundId, CONSUMER, RefundRequestedListener.TOPIC);
    }
}
