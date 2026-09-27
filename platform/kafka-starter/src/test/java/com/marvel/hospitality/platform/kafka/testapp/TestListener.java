package com.marvel.hospitality.platform.kafka.testapp;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** Counts invocations per record key and fails on demand, so tests can see exactly what the error handler did. */
@Component
public class TestListener {

    public static final String TOPIC = "kafka-starter-test";

    private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();
    private final Map<String, Boolean> succeeded = new ConcurrentHashMap<>();
    private final Map<String, String> traceIds = new ConcurrentHashMap<>();
    private final Map<String, String> mdcTraceIds = new ConcurrentHashMap<>();
    private final Tracer tracer;

    public TestListener(Tracer tracer) {
        this.tracer = tracer;
    }

    @KafkaListener(topics = TOPIC)
    void on(@Valid @Payload TestMessage message, @Header(KafkaHeaders.RECEIVED_KEY) String key, Acknowledgment ack) {
        int attempt = invocations.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        Span span = tracer.currentSpan();
        if (span != null) {
            traceIds.put(key, span.context().traceId());
        }
        String mdcTraceId = MDC.get("traceId");
        if (mdcTraceId != null) {
            mdcTraceIds.put(key, mdcTraceId);
        }
        switch (message.behaviour()) {
            case "transient" -> throw new IllegalStateException("simulated technical failure, attempt " + attempt);
            case "flaky" -> {
                if (attempt <= 2) {
                    throw new IllegalStateException("simulated technical failure, attempt " + attempt);
                }
            }
            case "contract" -> throw new IllegalArgumentException("simulated contract violation");
            default -> {
                // ok
            }
        }
        succeeded.put(key, true);
        ack.acknowledge();
    }

    public int invocations(String key) {
        AtomicInteger count = invocations.get(key);
        return count == null ? 0 : count.get();
    }

    /** The trace id of the span the listener ran in for this key, if any. */
    public @Nullable String traceId(String key) {
        return traceIds.get(key);
    }

    /** The {@code traceId} the MDC held while the listener ran for this key, if any. */
    public @Nullable String mdcTraceId(String key) {
        return mdcTraceIds.get(key);
    }

    public boolean succeeded(String key) {
        return succeeded.getOrDefault(key, false);
    }
}
