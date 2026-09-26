package com.marvel.hospitality.reservation.domain;

import java.util.regex.Pattern;

/**
 * The business identifier that leaves the service on the wire (identifiers.md): exactly 8 characters,
 * {@code P} followed by 7 characters from the Crockford base32 alphabet — no {@code I}, {@code L}, {@code O}
 * or {@code U}, which are easy to confuse with {@code 1}/{@code 0} when a guest reads this off a receipt to
 * type into a bank transfer. Global uniqueness across properties is a DB unique index concern
 * ({@link ReservationIdGenerator} only produces well-formed candidates, it does not check uniqueness).
 */
public record ReservationId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^P[0-9A-HJKMNP-TV-Z]{7}$");

    public ReservationId {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a valid reservationId: " + value);
        }
    }

    public static ReservationId of(String value) {
        return new ReservationId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
