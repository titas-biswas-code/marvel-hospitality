package com.marvel.hospitality.reservation.api;

/**
 * Stable {@code code} extension values this service adds to {@code https://marvel-hospitality/problems/<code>},
 * kept in one place so {@link ReservationProblemAdvice} and its springdoc examples never drift apart. Matches
 * rest-api.md's error code list.
 */
final class ProblemCodes {

    static final String PROPERTY_NOT_FOUND = "PROPERTY_NOT_FOUND";
    static final String ROOM_NOT_FOUND = "ROOM_NOT_FOUND";
    static final String RESERVATION_NOT_FOUND = "RESERVATION_NOT_FOUND";
    static final String ROOM_UNAVAILABLE = "ROOM_UNAVAILABLE";
    static final String PAYMENT_REFERENCE_ALREADY_USED = "PAYMENT_REFERENCE_ALREADY_USED";
    static final String ROOM_SEGMENT_MISMATCH = "ROOM_SEGMENT_MISMATCH";
    static final String BANK_TRANSFER_LEAD_TIME_TOO_SHORT = "BANK_TRANSFER_LEAD_TIME_TOO_SHORT";
    static final String PAYMENT_REJECTED = "PAYMENT_REJECTED";
    static final String PAYMENT_SERVICE_UNAVAILABLE = "PAYMENT_SERVICE_UNAVAILABLE";

    private ProblemCodes() {
    }
}
