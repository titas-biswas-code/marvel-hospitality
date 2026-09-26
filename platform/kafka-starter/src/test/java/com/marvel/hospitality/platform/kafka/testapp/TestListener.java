package com.marvel.hospitality.platform.kafka.testapp;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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

    @KafkaListener(topics = TOPIC)
    void on(@Valid @Payload TestMessage message, @Header(KafkaHeaders.RECEIVED_KEY) String key, Acknowledgment ack) {
        int attempt = invocations.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
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

    public boolean succeeded(String key) {
        return succeeded.getOrDefault(key, false);
    }
}
