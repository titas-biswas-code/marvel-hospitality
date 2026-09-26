package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.PaymentMatch;
import com.marvel.hospitality.reservation.domain.PaymentMatchOutcome;
import com.marvel.hospitality.reservation.domain.PaymentMatcher;
import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import com.marvel.hospitality.reservation.domain.Remittance;
import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Applies one bank payment (saga step 3 of ADR-0006) in a single local transaction:
 * <ol>
 *   <li>inbox: a {@code paymentId} seen before is a redelivery and changes nothing;</li>
 *   <li>store the payment in {@code received_payment} whatever happens next: an unreadable description or an unknown
 *       reservation is a business outcome, not an error (ADR-0008), and the money must stay visible for
 *       reconciliation (ADR-0009);</li>
 *   <li>lock the reservation, add this payment to the sum of its earlier matched payments and let
 *       {@link PaymentMatcher} classify the result; the reservation then records a partial payment or confirms,
 *       and its status event goes to the outbox;</li>
 *   <li>hand any refund that is due to {@link RefundPolicy}.</li>
 * </ol>
 * Because the sum is recomputed from stored payments under the row lock, the result does not depend on the order in
 * which payments arrive, and two concurrent payments for one reservation cannot overwrite each other's amount.
 *
 * <p>A technical failure anywhere rolls the whole transaction back, the inbox row included, so the redelivered
 * message is applied from scratch.
 */
public class ApplyBankPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyBankPaymentUseCase.class);

    private final PaymentInbox inbox;
    private final PaymentMatcher matcher;
    private final ReservationRepository reservations;
    private final ReceivedPaymentRepository payments;
    private final OutboxWriter outbox;
    private final RefundPolicy refundPolicy;
    private final TransactionOperations transactions;
    private final Clock clock;

    public ApplyBankPaymentUseCase(PaymentInbox inbox, PaymentMatcher matcher, ReservationRepository reservations,
            ReceivedPaymentRepository payments, OutboxWriter outbox, RefundPolicy refundPolicy,
            TransactionOperations transactions, Clock clock) {
        this.inbox = inbox;
        this.matcher = matcher;
        this.reservations = reservations;
        this.payments = payments;
        this.outbox = outbox;
        this.refundPolicy = refundPolicy;
        this.transactions = transactions;
        this.clock = clock;
    }

    public ApplyBankPaymentResult apply(ApplyBankPaymentCommand command) {
        Objects.requireNonNull(command, "command");
        return Objects.requireNonNull(transactions.execute(status -> applyInTransaction(command)));
    }

    private ApplyBankPaymentResult applyInTransaction(ApplyBankPaymentCommand command) {
        if (!inbox.firstDelivery(command.paymentId())) {
            log.debug("Payment {} already applied; duplicate delivery skipped", command.paymentId());
            return ApplyBankPaymentResult.duplicate();
        }
        Instant receivedAt = Instant.now(clock);
        Optional<Remittance> remittance = matcher.parse(command.transactionDescription());
        if (remittance.isEmpty()) {
            payments.add(unlinked(command, null, PaymentMatchOutcome.UNMATCHED_FORMAT, receivedAt));
            log.warn("Payment {} of {} kept for reconciliation: description '{}' does not name a reservation",
                    command.paymentId(), format(command.amount()), command.transactionDescription());
            return ApplyBankPaymentResult.applied(PaymentMatchOutcome.UNMATCHED_FORMAT);
        }
        ReservationId reservationId = remittance.get().reservationId();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("reservationId", reservationId.value())) {
            return applyToReservation(command, remittance.get(), receivedAt);
        }
    }

    private ApplyBankPaymentResult applyToReservation(ApplyBankPaymentCommand command, Remittance remittance,
            Instant receivedAt) {
        Optional<Reservation> found = reservations.findForUpdate(remittance.reservationId());
        if (found.isEmpty()) {
            PaymentMatch match = matcher.classify(null, Money.zeroEur(), command.amount());
            payments.add(unlinked(command, remittance.e2eId(), match.outcome(), receivedAt));
            log.warn("Payment {} of {} kept for reconciliation: no reservation {}",
                    command.paymentId(), format(command.amount()), remittance.reservationId());
            return ApplyBankPaymentResult.applied(match.outcome());
        }
        Reservation reservation = found.get();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("propertyId", reservation.propertyId())) {
            PaymentMatch match = matcher.classify(
                    reservation, payments.sumMatched(reservation.reservationId()), command.amount());
            ReceivedPayment payment = new ReceivedPayment(command.paymentId(), reservation.reservationId(),
                    reservation.propertyId(), command.debtorAccountNumber(), command.amount(),
                    command.transactionDescription(), remittance.e2eId(), match.outcome(), receivedAt);
            payments.add(payment);
            if (match.matched()) {
                Money amountReceived = Objects.requireNonNull(match.amountReceived());
                if (match.outcome() == PaymentMatchOutcome.MATCHED_PARTIAL) {
                    reservation.recordPartialPayment(amountReceived, clock);
                } else {
                    reservation.confirmPayment(amountReceived, clock);
                }
                reservations.update(reservation);
                reservation.pullEvents().forEach(outbox::append);
            }
            if (match.refund() != null) {
                refundPolicy.refundDue(payment, match.refund());
            }
            log.info("Payment {} of {} for reservation {}: {} (received {} of {})", command.paymentId(),
                    format(command.amount()), reservation.reservationId(), match.outcome(),
                    format(reservation.amountReceived()), format(reservation.totalAmount()));
            return ApplyBankPaymentResult.applied(match.outcome());
        }
    }

    private static String format(Money money) {
        return money.amount().toPlainString() + " " + money.currency();
    }

    private static ReceivedPayment unlinked(ApplyBankPaymentCommand command, @Nullable String e2eId,
            PaymentMatchOutcome outcome, Instant receivedAt) {
        return new ReceivedPayment(command.paymentId(), null, null, command.debtorAccountNumber(), command.amount(),
                command.transactionDescription(), e2eId, outcome, receivedAt);
    }
}
