package com.marvel.hospitality.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.marvel.hospitality.reservation.domain.BankTransferPaymentModeHandler;
import com.marvel.hospitality.reservation.domain.CashPaymentModeHandler;
import com.marvel.hospitality.reservation.domain.InvalidStayException;
import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.PaymentDeadlinePolicy;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.Property;
import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationIdGenerator;
import com.marvel.hospitality.reservation.domain.ReservationStatus;
import com.marvel.hospitality.reservation.domain.ReservationStatusChanged;
import com.marvel.hospitality.reservation.domain.Room;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

class CreateReservationUseCaseTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC);
    private static final Property AMS01 =
            new Property("AMS01", "Marvel Amsterdam", ZoneId.of("Europe/Amsterdam"), "NL00MARV0000000001");
    private static final Room ROOM_201 = new Room("AMS01", "201", RoomSegment.MEDIUM);
    private static final int MAX_ID_ATTEMPTS = 5;

    private final PropertyCatalog catalog = mock(PropertyCatalog.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private CreateReservationUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateReservationUseCase(catalog, reservations, outbox,
                Map.of(PaymentMode.CASH, new CashPaymentModeHandler(),
                        PaymentMode.BANK_TRANSFER, new BankTransferPaymentModeHandler(new PaymentDeadlinePolicy())),
                new ReservationIdGenerator(new Random(42)), TransactionOperations.withoutTransaction(), CLOCK,
                MAX_ID_ATTEMPTS);
        given(catalog.findProperty("AMS01")).willReturn(Optional.of(AMS01));
        given(catalog.findRoom("AMS01", "201")).willReturn(Optional.of(ROOM_201));
        given(catalog.findNightlyRate("AMS01", RoomSegment.MEDIUM)).willReturn(Optional.of(Money.eur("120.00")));
    }

    private static CreateReservationCommand command(PaymentMode mode) {
        return new CreateReservationCommand("AMS01", "Ada Lovelace", "201", LocalDate.parse("2026-10-10"),
                LocalDate.parse("2026-10-12"), RoomSegment.MEDIUM, mode, null);
    }

    @Test
    void storesReservationAndWritesItsCreationEventToTheOutbox() {
        ReservationView view = useCase.create(command(PaymentMode.BANK_TRANSFER));

        assertThat(view.reservation().status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        assertThat(view.bankTransferInstructions()).isEqualTo("Transfer 240.00 EUR to NL00MARV0000000001 "
                + "with description '<your E2E id> " + view.reservation().reservationId() + "'");
        verify(reservations).add(view.reservation());
        ArgumentCaptor<ReservationStatusChanged> event = ArgumentCaptor.forClass(ReservationStatusChanged.class);
        verify(outbox).append(event.capture());
        assertThat(event.getValue().previousStatus()).isNull();
        assertThat(event.getValue().status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
    }

    @Test
    void retriesWithNewIdOnCollision() {
        willThrow(new ReservationIdCollisionException("P0000000", new RuntimeException("duplicate key")))
                .willDoNothing()
                .given(reservations).add(any());

        ReservationView view = useCase.create(command(PaymentMode.CASH));

        ArgumentCaptor<Reservation> attempts = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations, times(2)).add(attempts.capture());
        assertThat(attempts.getAllValues().get(0).reservationId())
                .isNotEqualTo(attempts.getAllValues().get(1).reservationId());
        assertThat(view.reservation()).isSameAs(attempts.getAllValues().get(1));
        verify(outbox, times(1)).append(any());
    }

    @Test
    void failsAfterFiveCollisions() {
        willThrow(new ReservationIdCollisionException("P0000000", new RuntimeException("duplicate key")))
                .given(reservations).add(any());

        assertThatThrownBy(() -> useCase.create(command(PaymentMode.CASH)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("5 attempts");
        verify(reservations, times(MAX_ID_ATTEMPTS)).add(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void roomUnavailableIsNotRetried() {
        willThrow(new RoomUnavailableException("AMS01", "201", new RuntimeException("23P01")))
                .given(reservations).add(any());

        assertThatThrownBy(() -> useCase.create(command(PaymentMode.CASH))).isInstanceOf(RoomUnavailableException.class);
        verify(reservations, times(1)).add(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void rejectsUnknownProperty() {
        given(catalog.findProperty("AMS01")).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.create(command(PaymentMode.CASH))).isInstanceOf(PropertyNotFoundException.class);
        verifyNoInteractions(reservations, outbox);
    }

    @Test
    void rejectsUnknownRoom() {
        given(catalog.findRoom("AMS01", "201")).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.create(command(PaymentMode.CASH))).isInstanceOf(RoomNotFoundException.class);
        verifyNoInteractions(reservations, outbox);
    }

    @Test
    void creditCardHasNoHandlerInThisRelease() {
        assertThatThrownBy(() -> useCase.create(command(PaymentMode.CREDIT_CARD)))
                .isInstanceOf(PaymentModeNotSupportedException.class);
        verify(reservations, never()).add(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void doesNotStoreWhenDomainRulesRejectTheRequest() {
        willDoNothing().given(reservations).add(any());
        CreateReservationCommand tooLong = new CreateReservationCommand("AMS01", "Ada Lovelace", "201",
                LocalDate.parse("2026-10-10"), LocalDate.parse("2026-11-10"), RoomSegment.MEDIUM, PaymentMode.CASH, null);

        assertThatThrownBy(() -> useCase.create(tooLong))
                .isInstanceOf(InvalidStayException.class);
        verifyNoInteractions(reservations, outbox);
    }
}
