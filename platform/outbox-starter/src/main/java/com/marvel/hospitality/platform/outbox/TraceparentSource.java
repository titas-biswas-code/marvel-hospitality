package com.marvel.hospitality.platform.outbox;

import org.jspecify.annotations.Nullable;

/**
 * The W3C {@code traceparent} of the work that is writing an outbox row, stored in {@code outbox_event.traceparent}
 * so Debezium can copy it into the Kafka header and the consumer continues the same trace (ADR-0013,
 * docs/contracts/outbox-and-inbox.md).
 */
@FunctionalInterface
public interface TraceparentSource {

    /** The current {@code traceparent}, or {@code null} when no trace is active. */
    @Nullable String currentTraceparent();

    /** For a service without tracing: every row is written with a {@code null} {@code traceparent}. */
    static TraceparentSource none() {
        return () -> null;
    }
}
