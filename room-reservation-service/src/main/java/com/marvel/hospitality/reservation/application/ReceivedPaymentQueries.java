package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import com.marvel.hospitality.reservation.domain.Refund;
import com.marvel.hospitality.reservation.domain.ReservationId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read side of {@code received_payment} (rest-api.md): payments of one reservation, and the two reconciliation views
 * of payments that could not be applied (ADR-0009). Each payment comes with the refund it triggered, if any.
 * Read-only, so no transaction is opened.
 */
public class ReceivedPaymentQueries {

    private final PropertyCatalog catalog;
    private final ReservationRepository reservations;
    private final ReceivedPaymentRepository payments;
    private final RefundRepository refunds;

    public ReceivedPaymentQueries(PropertyCatalog catalog, ReservationRepository reservations,
            ReceivedPaymentRepository payments, RefundRepository refunds) {
        this.catalog = catalog;
        this.reservations = reservations;
        this.payments = payments;
        this.refunds = refunds;
    }

    /** @throws ReservationNotFoundException also when the reservation belongs to another property, or is malformed */
    public List<PaymentWithRefund> ofReservation(String propertyId, String reservationId) {
        ReservationId id;
        try {
            id = ReservationId.of(reservationId);
        } catch (IllegalArgumentException malformed) {
            throw new ReservationNotFoundException(propertyId, reservationId);
        }
        if (reservations.find(propertyId, id).isEmpty()) {
            throw new ReservationNotFoundException(propertyId, reservationId);
        }
        return withRefunds(payments.findByReservation(id));
    }

    /**
     * Payments for the property's reservations that arrived when the reservation was no longer awaiting payment
     * (cancelled or already confirmed). They are refunded automatically; this view lets staff follow them up.
     *
     * @throws PropertyNotFoundException the property does not exist
     */
    public List<PaymentWithRefund> notPendingOfProperty(String propertyId) {
        if (catalog.findProperty(propertyId).isEmpty()) {
            throw new PropertyNotFoundException(propertyId);
        }
        return withRefunds(payments.findNotPending(propertyId));
    }

    /**
     * Payments no reservation could be found for: unreadable description or unknown reservation id. They belong to
     * no property and are not refunded automatically, so a human can still match a typo (ADR-0009).
     */
    public List<PaymentWithRefund> withoutReservation() {
        return withRefunds(payments.findWithoutReservation());
    }

    private List<PaymentWithRefund> withRefunds(List<ReceivedPayment> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<String, Refund> byPayment = refunds.findByPaymentIds(found.stream().map(ReceivedPayment::paymentId).toList())
                .stream()
                .collect(Collectors.toMap(Refund::paymentId, Function.identity()));
        return found.stream()
                .map(payment -> new PaymentWithRefund(payment, byPayment.get(payment.paymentId())))
                .toList();
    }
}
