package com.marvel.hospitality.reservation.domain;

/**
 * A requested stay violates one of the date rules enforced by {@link StayPeriod}: start in the past, end not
 * after start, or more than {@link StayPeriod#MAX_NIGHTS} nights. The API layer maps this to
 * {@code 400 VALIDATION_FAILED} with a field-level detail (rest-api.md), hence {@link #field()}.
 */
public class InvalidStayException extends DomainException {

    private final String field;

    public InvalidStayException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
