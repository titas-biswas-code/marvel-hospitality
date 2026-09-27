package com.marvel.hospitality.reservation.infrastructure.kafka;

import com.marvel.hospitality.reservation.application.CompleteRefundResult;
import com.marvel.hospitality.reservation.application.CompleteRefundUseCase;
import com.marvel.hospitality.reservation.domain.RefundStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code refund-completed} (events.md): the payment service's answer to a {@code RefundRequested}. Same shape
 * as {@link BankTransferPaymentUpdateListener}: the platform Kafka starter supplies conversion, validation, retries and
 * the DLT (ADR-0008); this class hands the answer to {@link CompleteRefundUseCase} and acknowledges after its
 * transaction has committed.
 *
 * <p>{@code refund.failed} counts refunds the payment service could not pay out (first deliveries only, after the
 * commit): money the hotel still owes, which someone has to act on.
 */
@Component
class RefundCompletedListener {

    static final String TOPIC = "refund-completed";
    static final String LISTENER_ID = "refund-completed";
    static final String FAILED_METRIC = "refund.failed";

    private final CompleteRefundUseCase completeRefund;
    private final Counter failedRefunds;

    RefundCompletedListener(CompleteRefundUseCase completeRefund, MeterRegistry meterRegistry) {
        this.completeRefund = completeRefund;
        this.failedRefunds = Counter.builder(FAILED_METRIC)
                .description("Refunds the payment service reported as FAILED; each needs manual follow-up")
                .register(meterRegistry);
    }

    // idIsGroup = false: the id names the container (tests look it up); the group stays spring.kafka.consumer.group-id.
    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = TOPIC)
    void on(@Valid @Payload RefundCompletedMessage message, Acknowledgment ack) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("paymentId", message.paymentId())) {
            CompleteRefundResult result = completeRefund.complete(message.toCommand());
            ack.acknowledge();
            if (result.status() == RefundStatus.FAILED) {
                failedRefunds.increment();
            }
        }
    }
}
