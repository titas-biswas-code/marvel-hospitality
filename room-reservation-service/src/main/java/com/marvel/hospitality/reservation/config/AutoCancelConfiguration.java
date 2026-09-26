package com.marvel.hospitality.reservation.config;

import com.marvel.hospitality.reservation.application.CancelOverdueReservationUseCase;
import com.marvel.hospitality.reservation.application.OutboxWriter;
import com.marvel.hospitality.reservation.application.OverduePaymentReservationQuery;
import com.marvel.hospitality.reservation.application.ReservationRepository;
import com.marvel.hospitality.reservation.infrastructure.scheduling.AutoCancelJob;
import com.marvel.hospitality.reservation.infrastructure.scheduling.AutoCancelProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires ADR-0010's auto-cancel scheduler. Only the {@link AutoCancelJob} bean (and the use case behind it) is gated
 * by {@code reservation.auto-cancel.enabled}; scheduling itself is already turned on service-wide by
 * {@code platform/outbox-starter}'s {@code OutboxPurgeJob}, so this class must never add its own
 * {@code @EnableScheduling}: it would change nothing, and whoever read it would take it for the switch. Tests that
 * want the job enable the property themselves.
 *
 * <p>The {@code REQUIRES_NEW} {@link TransactionTemplate} is built here, as a plain local variable, rather than
 * exposed as its own bean: the service's other {@link TransactionTemplate} bean (the one autowired into
 * {@code CreateReservationUseCase} and {@code ApplyBankPaymentUseCase}) must keep its default propagation, and nothing
 * else in this service is meant to run in {@code REQUIRES_NEW} by accident.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "reservation.auto-cancel", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AutoCancelProperties.class)
class AutoCancelConfiguration {

    @Bean
    CancelOverdueReservationUseCase cancelOverdueReservationUseCase(OverduePaymentReservationQuery overdue,
            ReservationRepository reservations, OutboxWriter outbox, PlatformTransactionManager transactionManager,
            Clock clock) {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new CancelOverdueReservationUseCase(overdue, reservations, outbox, requiresNew, clock);
    }

    @Bean
    AutoCancelJob autoCancelJob(OverduePaymentReservationQuery overdue, CancelOverdueReservationUseCase cancelOverdue,
            MeterRegistry meterRegistry, Clock clock, AutoCancelProperties properties) {
        return new AutoCancelJob(overdue, cancelOverdue, meterRegistry, clock, properties.batchSize());
    }
}
