package com.marvel.hospitality.reservation.domain;

import java.util.Objects;

/**
 * A parsed {@code transactionDescription} from the bank (identifiers.md): the guest's E2E id followed by the
 * {@link ReservationId} they were asked to quote. The E2E id is opaque to us — it is stored on
 * {@link ReceivedPayment} for audit only (ADR-0009 §2), never used for matching, so it is kept as a plain
 * {@code String} rather than a validated value type.
 */
public record Remittance(String e2eId, ReservationId reservationId) {

    public Remittance {
        Objects.requireNonNull(e2eId, "e2eId");
        Objects.requireNonNull(reservationId, "reservationId");
    }
}
