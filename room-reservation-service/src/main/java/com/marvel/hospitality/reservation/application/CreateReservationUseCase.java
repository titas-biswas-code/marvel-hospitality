package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.Money;
import com.marvel.hospitality.reservation.domain.NewReservation;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.PaymentModeHandler;
import com.marvel.hospitality.reservation.domain.Property;
import com.marvel.hospitality.reservation.domain.Reservation;
import com.marvel.hospitality.reservation.domain.ReservationId;
import com.marvel.hospitality.reservation.domain.ReservationIdGenerator;
import com.marvel.hospitality.reservation.domain.Room;
import com.marvel.hospitality.reservation.domain.StayPeriod;
import java.time.Clock;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Creates a reservation: reference-data lookups, an optional payment verification, then one local transaction that
 * inserts the reservation and its {@code ReservationStatusChanged} outbox row (ADR-0006).
 *
 * <p>Payment verification (credit card, ADR-0011) may call a remote service, so it runs <em>before</em> the
 * transaction opens and never inside it. Ahead of that call the use case runs the mode-independent creation rules
 * ({@link Reservation#checkCreatable}) and a lock-free availability read, so a request that is bound to fail never
 * costs a remote call. The exclusion constraint stays the final word on availability: a room booked by someone else
 * between the check and the insert still ends in {@link RoomUnavailableException}. If the store fails after a
 * payment was verified, the guest has paid without getting a reservation; that is logged at WARN with the payment
 * reference for manual reconciliation (no refund/void operation exists in the credit-card contract).
 *
 * <p>The transaction is opened here with {@link TransactionOperations} rather than {@code @Transactional} because of
 * the id retry: a unique-index violation aborts the Postgres transaction, so each attempt with a fresh
 * {@link ReservationId} needs a transaction of its own. Overlap ({@link RoomUnavailableException}) is never retried.
 */
public class CreateReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateReservationUseCase.class);

    private final PropertyCatalog catalog;
    private final ReservationRepository reservations;
    private final OutboxWriter outbox;
    private final Map<PaymentMode, PaymentModeHandler> handlers;
    private final Map<PaymentMode, PaymentVerification> verifications;
    private final ReservationIdGenerator idGenerator;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final int maxIdAttempts;

    /**
     * @param handlers one per {@link PaymentMode}, all modes covered — a missing one fails here, at startup, rather
     *        than as an error response to the first guest who picks that mode
     * @param verifications only for the modes that need a check before storing; may be empty
     */
    public CreateReservationUseCase(PropertyCatalog catalog, ReservationRepository reservations, OutboxWriter outbox,
            Map<PaymentMode, PaymentModeHandler> handlers, Map<PaymentMode, PaymentVerification> verifications,
            ReservationIdGenerator idGenerator, TransactionOperations transactions, Clock clock, int maxIdAttempts) {
        if (maxIdAttempts < 1) {
            throw new IllegalArgumentException("maxIdAttempts must be at least 1: " + maxIdAttempts);
        }
        Set<PaymentMode> missing = EnumSet.allOf(PaymentMode.class);
        missing.removeAll(handlers.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("No PaymentModeHandler for payment mode(s) " + missing);
        }
        this.catalog = catalog;
        this.reservations = reservations;
        this.outbox = outbox;
        this.handlers = new EnumMap<>(handlers);
        this.verifications = verifications.isEmpty() ? Map.of() : new EnumMap<>(verifications);
        this.idGenerator = idGenerator;
        this.transactions = transactions;
        this.clock = clock;
        this.maxIdAttempts = maxIdAttempts;
    }

    /**
     * @throws PropertyNotFoundException, RoomNotFoundException, RoomUnavailableException, the domain's creation-rule
     *         exceptions ({@code InvalidStayException}, {@code RoomSegmentMismatchException},
     *         {@code BankTransferLeadTimeTooShortException}) and, for modes with a {@link PaymentVerification},
     *         {@link PaymentReferenceAlreadyUsedException}, {@link PaymentRejectedException} /
     *         {@link PaymentServiceUnavailableException}
     */
    public ReservationView create(CreateReservationCommand command) {
        Property property = catalog.findProperty(command.propertyId())
                .orElseThrow(() -> new PropertyNotFoundException(command.propertyId()));
        Room room = catalog.findRoom(command.propertyId(), command.roomNumber())
                .orElseThrow(() -> new RoomNotFoundException(command.propertyId(), command.roomNumber()));
        PaymentModeHandler handler = handlers.get(command.paymentMode());
        // Priced by the room's actual segment; a mismatching request is rejected by Reservation.create.
        Money nightlyRate = catalog.findNightlyRate(property.id(), room.segment())
                .orElseThrow(() -> new IllegalStateException(
                        "No nightly rate for segment " + room.segment() + " in property " + property.id()));

        PaymentVerification verification = verifications.get(command.paymentMode());
        if (verification == null) {
            return store(command, property, room, nightlyRate, handler);
        }
        verifyBeforeStoring(command, property, room, verification);
        try {
            return store(command, property, room, nightlyRate, handler);
        } catch (PaymentReferenceAlreadyUsedException ex) {
            // Lost a race with a request carrying the same payment: that payment backs the other reservation, so
            // there is nothing to reconcile.
            throw ex;
        } catch (RuntimeException ex) {
            log.atWarn()
                    .addKeyValue("propertyId", property.id())
                    .addKeyValue("paymentMode", command.paymentMode())
                    .addKeyValue("paymentReference", command.paymentReference())
                    .addKeyValue("reason", ex.getClass().getSimpleName())
                    .log("Payment verified but reservation not created; needs manual reconciliation");
            throw ex;
        }
    }

    /**
     * Everything that can reject the request cheaply runs first; the remote payment check runs last. No tx open while
     * it runs. The payment-reference check comes before availability: a guest re-sending a request whose first
     * attempt already succeeded learns that their payment is used, not merely that the room is taken.
     */
    private void verifyBeforeStoring(
            CreateReservationCommand command, Property property, Room room, PaymentVerification verification) {
        StayPeriod stay = Reservation.checkCreatable(
                command.startDate(), command.endDate(), command.roomSegment(), property, room, clock);
        String paymentReference = Objects.requireNonNull(command.paymentReference(), "paymentReference");
        PreCheck preCheck = transactions.execute(status -> {
            if (reservations.isPaymentReferenceUsed(command.paymentMode(), paymentReference)) {
                return PreCheck.PAYMENT_REFERENCE_USED;
            }
            return reservations.isBooked(property.id(), room.roomNumber(), stay) ? PreCheck.ROOM_BOOKED : PreCheck.OK;
        });
        if (preCheck == PreCheck.PAYMENT_REFERENCE_USED) {
            throw new PaymentReferenceAlreadyUsedException(command.paymentMode(), paymentReference);
        }
        if (preCheck == PreCheck.ROOM_BOOKED) {
            throw new RoomUnavailableException(property.id(), room.roomNumber());
        }
        verification.verify(command);
    }

    private enum PreCheck { OK, PAYMENT_REFERENCE_USED, ROOM_BOOKED }

    private ReservationView store(
            CreateReservationCommand command, Property property, Room room, Money nightlyRate, PaymentModeHandler handler) {
        for (int attempt = 1; ; attempt++) {
            ReservationId reservationId = idGenerator.next();
            try {
                Reservation reservation = transactions.execute(status ->
                        createAndStore(command, reservationId, property, room, nightlyRate, handler));
                log.atInfo()
                        .addKeyValue("propertyId", property.id())
                        .addKeyValue("reservationId", reservationId.value())
                        .addKeyValue("status", reservation.status())
                        .log("Reservation created");
                return new ReservationView(reservation, reservation.bankTransferInstructions(property.bankAccountNumber()));
            } catch (ReservationIdCollisionException collision) {
                if (attempt >= maxIdAttempts) {
                    throw new IllegalStateException(
                            "Could not allocate a unique reservation id in " + maxIdAttempts + " attempts", collision);
                }
                log.atWarn().addKeyValue("reservationId", reservationId.value()).addKeyValue("attempt", attempt)
                        .log("Reservation id collision, retrying with a new id");
            }
        }
    }

    private Reservation createAndStore(CreateReservationCommand command, ReservationId reservationId, Property property,
            Room room, Money nightlyRate, PaymentModeHandler handler) {
        NewReservation request = new NewReservation(reservationId, command.customerName(), command.startDate(),
                command.endDate(), command.roomSegment(), command.paymentMode(), command.paymentReference());
        Reservation reservation = Reservation.create(request, property, room, nightlyRate, handler, clock);
        reservations.add(reservation);
        reservation.pullEvents().forEach(outbox::append);
        return reservation;
    }
}
