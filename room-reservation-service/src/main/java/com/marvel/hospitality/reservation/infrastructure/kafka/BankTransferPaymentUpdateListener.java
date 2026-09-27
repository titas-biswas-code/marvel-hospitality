package com.marvel.hospitality.reservation.infrastructure.kafka;

import com.marvel.hospitality.platform.observability.LoggingContext;
import com.marvel.hospitality.reservation.application.ApplyBankPaymentResult;
import com.marvel.hospitality.reservation.application.ApplyBankPaymentUseCase;
import com.marvel.hospitality.reservation.domain.RefundReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code bank-transfer-payment-update} (events.md). The platform Kafka starter supplies the policy
 * (ADR-0008): JSON is converted and validated before this method runs, technical failures are retried and then
 * dead-lettered. This class only hands the payment to {@link ApplyBankPaymentUseCase}, whose transaction has
 * committed when it returns, and acknowledges afterwards, so a crash in between redelivers the record and the inbox
 * turns the redelivery into a no-op.
 *
 * <p>{@code payment.matched{outcome}} and {@code refund.requested{reason}} count first deliveries only, after the
 * commit (a duplicate delivery changes nothing, so it requests no refund either).
 */
@Component
class BankTransferPaymentUpdateListener {

    static final String TOPIC = "bank-transfer-payment-update";
    static final String LISTENER_ID = "bank-transfer-payment-update";
    static final String MATCHED_METRIC = "payment.matched";
    static final String REFUND_REQUESTED_METRIC = "refund.requested";

    private final ApplyBankPaymentUseCase applyBankPayment;
    private final MeterRegistry meterRegistry;

    BankTransferPaymentUpdateListener(ApplyBankPaymentUseCase applyBankPayment, MeterRegistry meterRegistry) {
        this.applyBankPayment = applyBankPayment;
        this.meterRegistry = meterRegistry;
    }

    // idIsGroup = false: the id names the container (tests look it up); the group stays spring.kafka.consumer.group-id.
    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = TOPIC)
    void on(@Valid @Payload BankTransferPaymentUpdateMessage message, Acknowledgment ack) {
        try (LoggingContext ignored = LoggingContext.create().paymentId(message.paymentId())) {
            ApplyBankPaymentResult result = applyBankPayment.apply(message.toCommand());
            ack.acknowledge();
            if (!result.isDuplicate()) {
                Counter.builder(MATCHED_METRIC)
                        .description("Bank payments applied, by matching outcome (ADR-0009)")
                        .tag("outcome", Objects.requireNonNull(result.outcome()).name())
                        .register(meterRegistry)
                        .increment();
            }
            RefundReason refundReason = result.refundRequested();
            if (refundReason != null) {
                Counter.builder(REFUND_REQUESTED_METRIC)
                        .description("Refunds requested along with a payment, by reason (ADR-0009, ADR-0013)")
                        .tag("reason", refundReason.name())
                        .register(meterRegistry)
                        .increment();
            }
        }
    }
}
