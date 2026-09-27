package com.marvel.hospitality.platform.observability;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Puts the business ids every log line should carry ({@code propertyId}, {@code reservationId}, {@code paymentId},
 * {@code refundId}) into the SLF4J MDC for one block of work, so every log line inside it — ECS console JSON and the
 * OTLP records shipped to Loki — carries them (ADR-0013). {@code traceId} and {@code spanId} are not set here:
 * Micrometer Tracing puts those into the MDC for the current span.
 *
 * <pre>{@code
 * try (LoggingContext ignored = LoggingContext.create().propertyId(p).reservationId(r)) {
 *     ...
 * }
 * }</pre>
 *
 * <p>Closing a context <em>restores</em> each key to the value it had before, instead of removing it: a listener sets
 * {@code paymentId}, the use case it calls opens its own context for {@code reservationId}, and when the use case
 * returns the listener's {@code paymentId} must still be there. A {@code null} value is skipped, so an id that is
 * not known yet never overwrites one that is. A context belongs to the thread that created it, like the MDC itself.
 */
public final class LoggingContext implements AutoCloseable {

    public static final String PROPERTY_ID = "propertyId";
    public static final String RESERVATION_ID = "reservationId";
    public static final String PAYMENT_ID = "paymentId";
    public static final String REFUND_ID = "refundId";

    /** Every key this class manages; the OTLP log appender copies exactly these from the MDC onto each log record. */
    public static final List<String> KEYS = List.of(PROPERTY_ID, RESERVATION_ID, PAYMENT_ID, REFUND_ID);

    private final Deque<Previous> previous = new ArrayDeque<>();

    private LoggingContext() {
    }

    /** An empty context; add ids with the fluent setters, each of which takes effect immediately. */
    public static LoggingContext create() {
        return new LoggingContext();
    }

    public LoggingContext propertyId(@Nullable Object value) {
        return put(PROPERTY_ID, value);
    }

    public LoggingContext reservationId(@Nullable Object value) {
        return put(RESERVATION_ID, value);
    }

    public LoggingContext paymentId(@Nullable Object value) {
        return put(PAYMENT_ID, value);
    }

    public LoggingContext refundId(@Nullable Object value) {
        return put(REFUND_ID, value);
    }

    private LoggingContext put(String key, @Nullable Object value) {
        if (value == null) {
            return this;
        }
        previous.push(new Previous(key, MDC.get(key)));
        MDC.put(key, value.toString());
        return this;
    }

    /** Puts back what each key held before this context set it, newest first. */
    @Override
    public void close() {
        while (!previous.isEmpty()) {
            Previous entry = previous.pop();
            if (entry.value() == null) {
                MDC.remove(entry.key());
            } else {
                MDC.put(entry.key(), entry.value());
            }
        }
    }

    private record Previous(String key, @Nullable String value) {
    }
}
