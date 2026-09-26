package com.marvel.hospitality.platform.problem.testapp;

import com.marvel.hospitality.platform.problem.Problems;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Stands in for a real service's own advice: ordered ahead of {@code FallbackProblemAdvice} exactly as
 * the project conventions and {@link Problems#SERVICE_ADVICE_ORDER} require.
 */
@RestControllerAdvice
@Order(Problems.SERVICE_ADVICE_ORDER)
public class ServiceProblemAdvice {

    public static final String CODE = "SERVICE_SPECIFIC";

    @ExceptionHandler(ServiceSpecificException.class)
    ProblemDetail handleServiceSpecific(ServiceSpecificException ex) {
        return Problems.of(HttpStatus.CONFLICT, CODE, ex.getMessage());
    }
}
