/**
 * Observability every service shares (ADR-0013): Boot's OpenTelemetry starter for traces and metrics over OTLP,
 * Logback bridged to the OTLP log exporter so logs reach Loki with the MDC ids, a {@code service} tag on every meter,
 * and {@link com.marvel.hospitality.platform.observability.LoggingContext} to set those MDC ids.
 */
@NullMarked
package com.marvel.hospitality.platform.observability;

import org.jspecify.annotations.NullMarked;
