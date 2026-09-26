package com.marvel.hospitality.reservation.api;

import com.marvel.hospitality.reservation.domain.CancellationReason;
import com.marvel.hospitality.reservation.domain.PaymentMatchOutcome;
import com.marvel.hospitality.reservation.domain.PaymentMode;
import com.marvel.hospitality.reservation.domain.RefundReason;
import com.marvel.hospitality.reservation.domain.ReservationStatus;
import com.marvel.hospitality.reservation.domain.RoomSegment;
import java.util.Arrays;
import java.util.List;

/**
 * The {@code GET /reference-data} body (rest-api.md): every enum this service exposes, built from
 * {@code Enum.values()} so the Java enums stay the single source of truth (ADR-0004) — no UI hardcodes these names.
 */
public record ReferenceDataResponse(
        List<String> reservationStatuses,
        List<String> paymentModes,
        List<String> roomSegments,
        List<String> paymentMatchOutcomes,
        List<String> refundReasons,
        List<String> cancellationReasons) {

    public static ReferenceDataResponse current() {
        return new ReferenceDataResponse(
                names(ReservationStatus.values()),
                names(PaymentMode.values()),
                names(RoomSegment.values()),
                names(PaymentMatchOutcome.values()),
                names(RefundReason.values()),
                names(CancellationReason.values()));
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
