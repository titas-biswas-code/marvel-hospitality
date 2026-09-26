package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.Reservation;
import org.jspecify.annotations.Nullable;

/**
 * A reservation plus what the API shows alongside it that lives on the property, i.e. the bank-transfer
 * instructions naming the property's account.
 */
public record ReservationView(Reservation reservation, @Nullable String bankTransferInstructions) {
}
