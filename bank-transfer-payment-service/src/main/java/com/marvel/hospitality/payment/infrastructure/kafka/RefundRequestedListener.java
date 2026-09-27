package com.marvel.hospitality.payment.infrastructure.kafka;

import com.marvel.hospitality.payment.application.ExecuteRefundUseCase;
import jakarta.validation.Valid;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code refund-requested} (events.md). The platform Kafka starter supplies the policy (ADR-0008): JSON is
 * converted and validated before this method runs, technical failures are retried and then dead-lettered. This class
 * only hands the request to {@link ExecuteRefundUseCase}, whose transaction has committed when it returns, and
 * acknowledges afterwards, so a crash in between redelivers the record and the inbox turns the redelivery into a
 * no-op.
 */
@Component
class RefundRequestedListener {

    static final String TOPIC = "refund-requested";
    static final String LISTENER_ID = "refund-requested";

    private final ExecuteRefundUseCase executeRefund;

    RefundRequestedListener(ExecuteRefundUseCase executeRefund) {
        this.executeRefund = executeRefund;
    }

    // idIsGroup = false: the id names the container (tests look it up); the group stays spring.kafka.consumer.group-id.
    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = TOPIC)
    void on(@Valid @Payload RefundRequestedMessage message, Acknowledgment ack) {
        executeRefund.execute(message.toCommand());
        ack.acknowledge();
    }
}
