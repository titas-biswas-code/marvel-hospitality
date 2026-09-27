package com.marvel.hospitality.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.marvel.hospitality.platform.kafka.test.ListenerAssignments;
import com.marvel.hospitality.platform.kafka.testapp.TestApplication;
import com.marvel.hospitality.platform.kafka.testapp.TestListener;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * A consumed record continues the trace its producer started (ADR-0013): Debezium copies
 * {@code outbox_event.traceparent} into the {@code traceparent} header, and the listener's span must be part of that
 * trace. Same context as {@link KafkaErrorHandlingTest}.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.application.name=kafka-starter-test-app",
        "spring.kafka.consumer.group-id=kafka-starter-test",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "marvel.kafka.retry.initial-interval=10ms",
        "marvel.kafka.retry.max-interval=40ms",
        "management.tracing.sampling.probability=1.0"})
@Import(SharedKafkaConfiguration.class)
class KafkaTracePropagationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    TestListener listener;

    @Autowired
    KafkaListenerEndpointRegistry registry;

    @BeforeEach
    void allPartitionsAreAssigned() {
        ListenerAssignments.awaitFullyAssigned(registry, 3, TIMEOUT);
    }

    @Test
    void listenerContinuesTraceFromTraceparentHeader() throws Exception {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String key = UUID.randomUUID().toString();
        ProducerRecord<String, String> record = new ProducerRecord<>(TestListener.TOPIC, key, """
                {"behaviour":"ok"}""");
        record.headers().add("traceparent", ("00-" + traceId + "-00f067aa0ba902b7-01").getBytes());

        kafkaTemplate.send(record).get();

        await().atMost(TIMEOUT).until(() -> listener.succeeded(key));
        assertThat(listener.traceId(key)).isEqualTo(traceId);
        assertThat(listener.mdcTraceId(key)).as("traceId in the listener's log lines").isEqualTo(traceId);
    }

    @Test
    void listenerToleratesNullTraceparentHeader() throws Exception {
        // What Debezium sends for an outbox row written outside any trace: the header, with a null value.
        String key = UUID.randomUUID().toString();
        ProducerRecord<String, String> record = new ProducerRecord<>(TestListener.TOPIC, key, """
                {"behaviour":"ok"}""");
        record.headers().add(new RecordHeader("traceparent", null));

        kafkaTemplate.send(record).get();

        await().atMost(TIMEOUT).until(() -> listener.succeeded(key));
        assertThat(listener.invocations(key)).isEqualTo(1);
        assertThat(listener.traceId(key)).as("a new trace is started").isNotBlank();
    }
}
