package com.marvel.hospitality.reservation.domain;

/**
 * Base type for domain rule violations raised by the reservation aggregate and its value objects. Subclasses
 * carry whatever detail the API layer needs to build a {@code ProblemDetail} (rest-api.md); the domain itself
 * never depends on HTTP status codes or Spring types.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
