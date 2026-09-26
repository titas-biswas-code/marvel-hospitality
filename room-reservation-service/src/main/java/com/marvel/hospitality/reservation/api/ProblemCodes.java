package com.marvel.hospitality.reservation.api;

/**
 * Stable {@code code} extension values this service adds to {@code https://marvel-hospitality/problems/<code>},
 * kept in one place so {@link ReservationProblemAdvice} and its springdoc examples never drift apart. Matches
 * rest-api.md's error code list, except {@link #NOT_IMPLEMENTED_YET} (see its own javadoc).
 */
final class ProblemCodes {

    static final String PROPERTY_NOT_FOUND = "PROPERTY_NOT_FOUND";
    static final String ROOM_NOT_FOUND = "ROOM_NOT_FOUND";
    static final String RESERVATION_NOT_FOUND = "RESERVATION_NOT_FOUND";
    static final String ROOM_UNAVAILABLE = "ROOM_UNAVAILABLE";
    static final String ROOM_SEGMENT_MISMATCH = "ROOM_SEGMENT_MISMATCH";
    static final String BANK_TRANSFER_LEAD_TIME_TOO_SHORT = "BANK_TRANSFER_LEAD_TIME_TOO_SHORT";

    /**
     * Temporary: {@code CREDIT_CARD} has no {@code PaymentModeHandler} registered until PR-03 adds one. This code
     * (and the {@code PaymentModeNotSupportedException} mapping that uses it) is removed in PR-03. Sanctioned by the
     * PR-02 plan; deliberately not in rest-api.md's error code list, since it never reaches the finished contract.
     */
    static final String NOT_IMPLEMENTED_YET = "NOT_IMPLEMENTED_YET";

    private ProblemCodes() {
    }
}
