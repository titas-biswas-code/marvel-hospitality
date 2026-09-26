package com.marvel.hospitality.platform.kafka.testapp;

import jakarta.validation.constraints.NotBlank;

/**
 * @param behaviour what {@link TestListener} does with it: {@code ok}, {@code transient} (always a retryable failure),
 *        {@code flaky} (fails twice, then succeeds) or {@code contract} (an {@link IllegalArgumentException})
 */
public record TestMessage(@NotBlank String behaviour) {
}
