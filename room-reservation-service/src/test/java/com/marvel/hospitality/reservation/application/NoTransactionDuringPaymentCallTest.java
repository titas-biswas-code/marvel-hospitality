package com.marvel.hospitality.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.marvel.hospitality.reservation.MockJwtDecoderConfiguration;
import com.marvel.hospitality.reservation.TestcontainersConfiguration;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.ReservationStatus;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0011: a remote payment call must never run with a database transaction held open underneath it — holding a
 * connection (and any locks it took) for the whole round trip to another service would tie this service's DB pool
 * capacity to that service's latency, and a slow or hung payment service would eventually starve every other
 * request. {@link CreateReservationUseCase#verifyBeforeStoring} is written to call {@link PaymentVerification#verify}
 * strictly outside the transaction that later stores the reservation; this test proves that with a real
 * {@link org.springframework.transaction.PlatformTransactionManager} against real Postgres; the equivalent unit test
 * ({@code CreateReservationUseCaseTest}) only proves it against a recording fake of
 * {@link org.springframework.transaction.support.TransactionOperations}.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class NoTransactionDuringPaymentCallTest {

    @Autowired
    private CreateReservationUseCase createReservationUseCase;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private CreditCardPaymentClient creditCardPaymentClient;

    @Test
    void noDatabaseTransactionIsActiveDuringPaymentCall() {
        AtomicBoolean transactionActiveDuringCall = new AtomicBoolean(true);
        AtomicInteger callCount = new AtomicInteger();
        given(creditCardPaymentClient.retrieveStatus(anyString())).willAnswer(invocation -> {
            callCount.incrementAndGet();
            transactionActiveDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return CreditCardPaymentStatus.CONFIRMED;
        });

        CreateReservationCommand command = new CreateReservationCommand("AMS01", "Ada Lovelace", "301",
                LocalDate.parse("2032-01-10"), LocalDate.parse("2032-01-12"), RoomSegment.LARGE,
                PaymentMode.CREDIT_CARD, "NO-TX-1");

        ReservationView view = createReservationUseCase.create(command);

        assertThat(transactionActiveDuringCall).as("a transaction was active while the payment call was in flight").isFalse();
        assertThat(callCount).hasValue(1);
        assertThat(view.reservation().status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(view.reservation().paymentReference()).isEqualTo("NO-TX-1");
    }

    /**
     * Sanity check for the probe itself: run the identical check inside a transaction deliberately opened by the
     * test, so a future change that breaks {@link TransactionSynchronizationManager#isActualTransactionActive()}
     * detection (rather than a real regression in the use case) would fail loudly here instead of making the test
     * above pass for the wrong reason.
     */
    @Test
    void transactionProbeDetectsAnOpenTransaction() {
        boolean transactionActive = Boolean.TRUE.equals(transactionTemplate.execute(
                status -> TransactionSynchronizationManager.isActualTransactionActive()));

        assertThat(transactionActive).isTrue();
    }
}
