package com.marvel.hospitality.payment.application;

import com.marvel.hospitality.payment.domain.BankTransaction;
import com.marvel.hospitality.payment.domain.RefundInstruction;
import com.marvel.hospitality.payment.domain.RefundInstructionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code refund-requested} consumer's use case (ADR-0014, contracts/events.md): one local transaction that
 * decides the instruction's creditor and outcome and writes its {@code RefundCompleted} outbox row, or neither
 * (ADR-0006 step 2).
 *
 * <ol>
 *   <li>inbox: a {@code refundId} seen before is a redelivery and changes nothing;</li>
 *   <li>an unknown {@code paymentId} cannot back a {@code refund_instruction} row (its foreign key requires a real
 *       {@code bank_transaction}) — log ERROR and emit {@code RefundCompleted FAILED} with reason
 *       {@code UNKNOWN_PAYMENT}, still idempotently;</li>
 *   <li>a request for more than the original transaction ever received is failed without calling the payout rail
 *       (cheap safety net: never pay back more than came in);</li>
 *   <li>otherwise the instruction is created against the original debtor account and {@link RefundExecutor} decides
 *       {@code EXECUTED} or {@code FAILED}; only this service's stub allows calling it inside this transaction (see
 *       {@link RefundExecutor}'s javadoc).</li>
 * </ol>
 * A technical failure anywhere rolls the whole transaction back, the inbox row included, so the redelivered message
 * is applied from scratch.
 */
@Service
public class ExecuteRefundUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExecuteRefundUseCase.class);

    static final String UNKNOWN_PAYMENT = "UNKNOWN_PAYMENT";
    static final String AMOUNT_EXCEEDS_PAYMENT = "AMOUNT_EXCEEDS_PAYMENT";

    private final RefundInbox inbox;
    private final BankTransactionLedger ledger;
    private final RefundInstructionRepository refundInstructions;
    private final RefundExecutor executor;
    private final PaymentEventOutbox outbox;
    private final Clock clock;

    public ExecuteRefundUseCase(RefundInbox inbox, BankTransactionLedger ledger,
            RefundInstructionRepository refundInstructions, RefundExecutor executor, PaymentEventOutbox outbox,
            Clock clock) {
        this.inbox = inbox;
        this.ledger = ledger;
        this.refundInstructions = refundInstructions;
        this.executor = executor;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public ExecuteRefundResult execute(ExecuteRefundCommand command) {
        try (MDC.MDCCloseable ignoredRefundId = MDC.putCloseable("refundId", command.refundId().toString());
                MDC.MDCCloseable ignoredPaymentId = MDC.putCloseable("paymentId", command.paymentId().toString());
                MDC.MDCCloseable ignoredReservationId = MDC.putCloseable("reservationId", command.reservationId());
                MDC.MDCCloseable ignoredPropertyId = MDC.putCloseable("propertyId", command.propertyId())) {
            if (!inbox.firstDelivery(command.refundId().toString())) {
                log.debug("Refund {} already processed; duplicate delivery skipped", command.refundId());
                return ExecuteRefundResult.duplicate();
            }
            Instant now = clock.instant();
            Optional<BankTransaction> transaction = ledger.findByPaymentId(command.paymentId());
            if (transaction.isEmpty()) {
                log.error("Refund {} of {} {} requested for unknown payment {}", command.refundId(), command.amount(),
                        command.currency(), command.paymentId());
                outbox.refundCompleted(RefundCompletion.unknownPayment(command, UNKNOWN_PAYMENT, now));
                return ExecuteRefundResult.executed(RefundInstructionStatus.FAILED);
            }
            RefundInstruction outcome = decide(command, transaction.get(), now);
            refundInstructions.add(outcome);
            outbox.refundCompleted(RefundCompletion.from(outcome, now));
            if (outcome.status() == RefundInstructionStatus.EXECUTED) {
                log.info("Refund {} of {} {} executed to {}", command.refundId(), outcome.amount(),
                        outcome.currency(), outcome.creditorAccountNumber());
            } else {
                log.error("Refund {} of {} {} failed: {}", command.refundId(), outcome.amount(), outcome.currency(),
                        outcome.failureReason());
            }
            return ExecuteRefundResult.executed(outcome.status());
        }
    }

    private RefundInstruction decide(ExecuteRefundCommand command, BankTransaction transaction, Instant now) {
        RefundInstruction received = RefundInstruction.received(command.refundId(), command.paymentId(),
                command.reservationId(), command.propertyId(), transaction.debtorAccountNumber(), command.amount(),
                command.currency(), command.reason(), now);
        if (command.amount().compareTo(transaction.amount()) > 0) {
            return received.failed(AMOUNT_EXCEEDS_PAYMENT);
        }
        RefundExecution execution = executor.execute(received);
        return execution.successful()
                ? received.executed(now)
                : received.failed(Objects.requireNonNull(execution.failureReason()));
    }
}
