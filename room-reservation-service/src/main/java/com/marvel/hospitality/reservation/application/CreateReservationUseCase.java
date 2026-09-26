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
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Creates a reservation: reference-data lookups, then one local transaction that inserts the reservation and its
 * {@code ReservationStatusChanged} outbox row (ADR-0006).
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
    private final ReservationIdGenerator idGenerator;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final int maxIdAttempts;

    public CreateReservationUseCase(PropertyCatalog catalog, ReservationRepository reservations, OutboxWriter outbox,
            Map<PaymentMode, PaymentModeHandler> handlers, ReservationIdGenerator idGenerator,
            TransactionOperations transactions, Clock clock, int maxIdAttempts) {
        if (maxIdAttempts < 1) {
            throw new IllegalArgumentException("maxIdAttempts must be at least 1: " + maxIdAttempts);
        }
        this.catalog = catalog;
        this.reservations = reservations;
        this.outbox = outbox;
        this.handlers = handlers.isEmpty() ? Map.of() : new EnumMap<>(handlers);
        this.idGenerator = idGenerator;
        this.transactions = transactions;
        this.clock = clock;
        this.maxIdAttempts = maxIdAttempts;
    }

    /**
     * @throws PropertyNotFoundException, RoomNotFoundException, PaymentModeNotSupportedException, RoomUnavailableException
     *         and the domain's creation-rule exceptions ({@code InvalidStayException}, {@code RoomSegmentMismatchException},
     *         {@code BankTransferLeadTimeTooShortException})
     */
    public ReservationView create(CreateReservationCommand command) {
        Property property = catalog.findProperty(command.propertyId())
                .orElseThrow(() -> new PropertyNotFoundException(command.propertyId()));
        Room room = catalog.findRoom(command.propertyId(), command.roomNumber())
                .orElseThrow(() -> new RoomNotFoundException(command.propertyId(), command.roomNumber()));
        PaymentModeHandler handler = handlers.get(command.paymentMode());
        if (handler == null) {
            throw new PaymentModeNotSupportedException(command.paymentMode());
        }
        // Priced by the room's actual segment; a mismatching request is rejected by Reservation.create.
        Money nightlyRate = catalog.findNightlyRate(property.id(), room.segment())
                .orElseThrow(() -> new IllegalStateException(
                        "No nightly rate for segment " + room.segment() + " in property " + property.id()));

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
