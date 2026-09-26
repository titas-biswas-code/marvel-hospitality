package com.marvel.hospitality.platform.problem.testapp;

import jakarta.validation.constraints.NotBlank;

/** Drives the bean-validation and unknown-enum-value tests. */
public record ValidatedRequest(@NotBlank String name, TestEnum type) {

    public enum TestEnum {
        ALPHA, BETA
    }
}
