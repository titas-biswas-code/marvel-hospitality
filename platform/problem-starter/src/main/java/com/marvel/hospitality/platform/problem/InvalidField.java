package com.marvel.hospitality.platform.problem;

/**
 * One failing field reported under the {@code errors} extension property of a {@code VALIDATION_FAILED} problem
 * (docs/contracts/rest-api.md).
 *
 * @param field the field name; a dotted path with {@code [n]} array indices for nested/collection fields
 * @param message a client-safe description of what is wrong with the value
 */
public record InvalidField(String field, String message) {
}
