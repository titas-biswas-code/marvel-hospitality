/**
 * RFC 9457 {@link org.springframework.http.ProblemDetail} conventions shared by every Marvel service
 * (docs/contracts/rest-api.md): the {@code type}/{@code code} shape ({@link com.marvel.hospitality.platform.problem.Problems}),
 * a fallback {@code @RestControllerAdvice} for framework and unexpected exceptions
 * ({@link com.marvel.hospitality.platform.problem.FallbackProblemAdvice}), and constraint-name lookup for mapping
 * database violations by name rather than blanket-mapping them ({@link com.marvel.hospitality.platform.problem.ConstraintNames}).
 */
@NullMarked
package com.marvel.hospitality.platform.problem;

import org.jspecify.annotations.NullMarked;
