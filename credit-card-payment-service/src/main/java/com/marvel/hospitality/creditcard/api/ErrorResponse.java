package com.marvel.hospitality.creditcard.api;

/**
 * Error body for every non-200 response (src/main/resources/openapi/credit-card-payment-api.yaml: {@code ErrorResponse}).
 * This is the spec's own shape, not the rest of the monorepo's {@code ProblemDetail} (the REST-error
 * convention of rest-api.md): this service's contract is the provided OpenAPI spec, not marvel-hospitality's own
 * rest-api.md, so it stays honest to what was agreed.
 *
 * @param error human-readable error message
 */
public record ErrorResponse(String error) {
}
