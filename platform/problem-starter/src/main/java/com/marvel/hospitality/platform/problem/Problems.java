package com.marvel.hospitality.platform.problem;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds the RFC 9457 {@link ProblemDetail} shape every Marvel service uses (docs/contracts/rest-api.md):
 * {@code type = https://marvel-hospitality/problems/<code>} and an extension property {@code code} that repeats
 * that same last path segment, so a client can branch on {@code code} without parsing the URI.
 */
public final class Problems {

    /** Prefix of every problem {@code type} URI; appending a code (e.g. {@code ROOM_UNAVAILABLE}) completes it. */
    public static final String TYPE_PREFIX = "https://marvel-hospitality/problems/";

    /** A request body or parameter failed bean validation; see {@link #validationFailed}. */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    /** Anything unexpected: an unmapped exception, or a DB constraint violation no service code recognised. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /**
     * The {@code @Order} value every service-level {@code @RestControllerAdvice} must declare —
     * {@code @Order(Problems.SERVICE_ADVICE_ORDER)} — so that it is consulted before {@link FallbackProblemAdvice}.
     *
     * <p>Spring MVC's {@code ExceptionHandlerExceptionResolver} sorts every {@code @ControllerAdvice} bean by its
     * {@code @Order} (ties broken by declaration order, which is unreliable) and, for a thrown exception, asks each
     * advice in turn whether it declares a matching {@code @ExceptionHandler} method. It stops at the FIRST advice
     * with a match — it never consults a later, lower-priority advice even if that advice's handler would also
     * match. {@link FallbackProblemAdvice} is registered at {@code Ordered.LOWEST_PRECEDENCE} specifically so it is
     * always the last one asked, and its catch-all {@code @ExceptionHandler(Exception.class)} would otherwise
     * shadow nothing — but only if every service advice sorts ahead of it. A service advice that forgets
     * {@code @Order(SERVICE_ADVICE_ORDER)} defaults to {@code Ordered.LOWEST_PRECEDENCE} too, and which of two
     * equally-ordered advices wins is unspecified; declaring the order explicitly removes that ambiguity.
     */
    public static final int SERVICE_ADVICE_ORDER = 0;

    private Problems() {
    }

    /**
     * A problem detail for one precise, known error: {@code type} and the {@code code} extension property are both
     * derived from {@code code}, and {@code title} is the status's reason phrase.
     */
    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + code));
        problem.setTitle(HttpStatus.valueOf(status.value()).getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }

    /**
     * {@code 400 VALIDATION_FAILED} with the failing fields under the {@code errors} extension property.
     */
    public static ProblemDetail validationFailed(String detail, List<InvalidField> errors) {
        ProblemDetail problem = of(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, detail);
        problem.setProperty("errors", errors);
        return problem;
    }
}
