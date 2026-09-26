package com.marvel.hospitality.reservation.config;

import com.marvel.hospitality.reservation.application.ApplyBankPaymentUseCase;
import com.marvel.hospitality.reservation.application.CreateReservationUseCase;
import com.marvel.hospitality.reservation.application.CreditCardPaymentClient;
import com.marvel.hospitality.reservation.application.CreditCardPaymentVerification;
import com.marvel.hospitality.reservation.application.GetReservationUseCase;
import com.marvel.hospitality.reservation.application.OutboxWriter;
import com.marvel.hospitality.reservation.application.PaymentInbox;
import com.marvel.hospitality.reservation.application.PaymentVerification;
import com.marvel.hospitality.reservation.application.PropertyCatalog;
import com.marvel.hospitality.reservation.application.ReceivedPaymentQueries;
import com.marvel.hospitality.reservation.application.ReceivedPaymentRepository;
import com.marvel.hospitality.reservation.application.RefundPolicy;
import com.marvel.hospitality.reservation.application.ReservationRepository;
import com.marvel.hospitality.reservation.domain.BankTransferPaymentModeHandler;
import com.marvel.hospitality.reservation.domain.CashPaymentModeHandler;
import com.marvel.hospitality.reservation.domain.CreditCardPaymentModeHandler;
import com.marvel.hospitality.reservation.domain.PaymentDeadlinePolicy;
import com.marvel.hospitality.reservation.domain.PaymentMatcher;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.PaymentModeHandler;
import com.marvel.hospitality.reservation.domain.ReservationIdGenerator;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the Spring-free domain and application classes. Payment modes plug in as {@link PaymentModeHandler} beans
 * collected into a map (ADR-0004): adding a mode is one class plus one bean, with no switch to edit. A mode that must
 * check its payment remotely before storing (credit card, ADR-0011) adds a {@link PaymentVerification} bean too.
 * Bank payments arriving over Kafka are applied by {@link ApplyBankPaymentUseCase} (ADR-0009).
 */
@Configuration(proxyBeanMethods = false)
class ReservationConfiguration {

    @Bean
    PaymentDeadlinePolicy paymentDeadlinePolicy() {
        return new PaymentDeadlinePolicy();
    }

    @Bean
    CashPaymentModeHandler cashPaymentModeHandler() {
        return new CashPaymentModeHandler();
    }

    @Bean
    BankTransferPaymentModeHandler bankTransferPaymentModeHandler(PaymentDeadlinePolicy policy) {
        return new BankTransferPaymentModeHandler(policy);
    }

    @Bean
    CreditCardPaymentModeHandler creditCardPaymentModeHandler() {
        return new CreditCardPaymentModeHandler();
    }

    @Bean
    CreditCardPaymentVerification creditCardPaymentVerification(CreditCardPaymentClient client) {
        return new CreditCardPaymentVerification(client);
    }

    @Bean
    ReservationIdGenerator reservationIdGenerator() {
        return new ReservationIdGenerator();
    }

    @Bean
    CreateReservationUseCase createReservationUseCase(PropertyCatalog catalog, ReservationRepository reservations,
            OutboxWriter outbox, List<PaymentModeHandler> handlers, List<PaymentVerification> verifications,
            ReservationIdGenerator idGenerator, TransactionTemplate transactions, Clock clock,
            @Value("${reservation.id.max-attempts}") int maxIdAttempts) {
        return new CreateReservationUseCase(catalog, reservations, outbox,
                byMode(handlers, PaymentModeHandler::mode), byMode(verifications, PaymentVerification::mode),
                idGenerator, transactions, clock, maxIdAttempts);
    }

    @Bean
    GetReservationUseCase getReservationUseCase(PropertyCatalog catalog, ReservationRepository reservations) {
        return new GetReservationUseCase(catalog, reservations);
    }

    @Bean
    PaymentMatcher paymentMatcher() {
        return new PaymentMatcher();
    }

    @Bean
    ApplyBankPaymentUseCase applyBankPaymentUseCase(PaymentInbox inbox, PaymentMatcher matcher,
            ReservationRepository reservations, ReceivedPaymentRepository payments, OutboxWriter outbox,
            RefundPolicy refundPolicy, TransactionTemplate transactions, Clock clock) {
        return new ApplyBankPaymentUseCase(
                inbox, matcher, reservations, payments, outbox, refundPolicy, transactions, clock);
    }

    @Bean
    ReceivedPaymentQueries receivedPaymentQueries(PropertyCatalog catalog, ReservationRepository reservations,
            ReceivedPaymentRepository payments) {
        return new ReceivedPaymentQueries(catalog, reservations, payments);
    }

    private static <T> Map<PaymentMode, T> byMode(List<T> strategies, Function<T, PaymentMode> modeOf) {
        Map<PaymentMode, T> byMode = new EnumMap<>(PaymentMode.class);
        for (T strategy : strategies) {
            T previous = byMode.put(modeOf.apply(strategy), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two " + strategy.getClass().getSimpleName() + "s for payment mode " + modeOf.apply(strategy));
            }
        }
        return byMode;
    }
}
