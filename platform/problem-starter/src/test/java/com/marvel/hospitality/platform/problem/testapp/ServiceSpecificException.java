package com.marvel.hospitality.platform.problem.testapp;

/** A made-up business exception, handled by {@link ServiceProblemAdvice} — stands in for a real service's own. */
public class ServiceSpecificException extends RuntimeException {

    public ServiceSpecificException(String message) {
        super(message);
    }
}
