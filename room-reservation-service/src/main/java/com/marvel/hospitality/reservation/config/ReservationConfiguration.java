package com.marvel.hospitality.reservation.config;

import com.marvel.hospitality.reservation.application.CreateReservationUseCase;
import com.marvel.hospitality.reservation.application.GetReservationUseCase;
import com.marvel.hospitality.reservation.application.OutboxWriter;
import com.marvel.hospitality.reservation.application.PropertyCatalog;
import com.marvel.hospitality.reservation.application.ReservationRepository;
import com.marvel.hospitality.reservation.domain.BankTransferPaymentModeHandler;
import com.marvel.hospitality.reservation.domain.CashPaymentModeHandler;
import com.marvel.hospitality.reservation.domain.PaymentDeadlinePolicy;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.PaymentModeHandler;
import com.marvel.hospitality.reservation.domain.ReservationIdGenerator;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the Spring-free domain and application classes. Payment modes plug in as {@link PaymentModeHandler} beans
 * collected into a map (ADR-0004): adding a mode is one class plus one bean, with no switch to edit.
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
    ReservationIdGenerator reservationIdGenerator() {
        return new ReservationIdGenerator();
    }

    @Bean
    CreateReservationUseCase createReservationUseCase(PropertyCatalog catalog, ReservationRepository reservations,
            OutboxWriter outbox, List<PaymentModeHandler> handlers, ReservationIdGenerator idGenerator,
            TransactionTemplate transactions, Clock clock, @Value("${reservation.id.max-attempts}") int maxIdAttempts) {
        return new CreateReservationUseCase(catalog, reservations, outbox, byMode(handlers), idGenerator, transactions,
                clock, maxIdAttempts);
    }

    @Bean
    GetReservationUseCase getReservationUseCase(PropertyCatalog catalog, ReservationRepository reservations) {
        return new GetReservationUseCase(catalog, reservations);
    }

    private static Map<PaymentMode, PaymentModeHandler> byMode(List<PaymentModeHandler> handlers) {
        Map<PaymentMode, PaymentModeHandler> byMode = new EnumMap<>(PaymentMode.class);
        for (PaymentModeHandler handler : handlers) {
            PaymentModeHandler previous = byMode.put(handler.mode(), handler);
            if (previous != null) {
                throw new IllegalStateException("Two handlers for payment mode " + handler.mode());
            }
        }
        return byMode;
    }
}
