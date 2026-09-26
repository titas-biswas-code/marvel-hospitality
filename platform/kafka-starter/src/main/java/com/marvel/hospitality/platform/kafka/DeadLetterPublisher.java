package com.marvel.hospitality.platform.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;

/**
 * The last step of ADR-0008's error handling: publishes a record that could not be processed to {@code <topic>.DLT}
 * (same partition, so the same key stays on the same partition number), with spring-kafka's {@code kafka_dlt-*}
 * exception headers next to the original headers, then counts it in {@code kafka.dlt.messages{topic}}.
 *
 * <p>It owns a producer of its own, built from the service's producer settings (bootstrap servers, security) but with
 * serializers for both shapes a dead letter can have: the consumed {@code String}, or the raw {@code byte[]} that
 * {@code ErrorHandlingDeserializer} keeps when deserialization itself failed. It is deliberately not a
 * {@code KafkaTemplate} or {@code ProducerFactory} bean, which would switch off Boot's own auto-configured ones.
 * This is the only place a service's code base publishes with {@code KafkaTemplate}; domain events go through the
 * outbox (ADR-0006).
 *
 * <p>The recoverer waits for the broker's acknowledgement and throws if the send fails; the error handler then does
 * not commit the offset and the record is delivered again, so a dead letter is never lost silently.
 */
public class DeadLetterPublisher implements ConsumerRecordRecoverer, DisposableBean {

    /** Suffix of every dead-letter topic; infra creates {@code <topic>.DLT} next to each topic (events.md). */
    public static final String DLT_SUFFIX = ".DLT";
    /** Counter of dead-lettered records, tagged with the source {@code topic} (ADR-0008, ADR-0013). */
    public static final String DLT_METRIC = "kafka.dlt.messages";

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final DefaultKafkaProducerFactory<Object, Object> producerFactory;
    private final DeadLetterPublishingRecoverer recoverer;
    private final @Nullable MeterRegistry meterRegistry;

    public DeadLetterPublisher(ProducerFactory<?, ?> serviceProducerFactory, @Nullable MeterRegistry meterRegistry) {
        Map<String, Object> config = serviceProducerFactory.getConfigurationProperties();
        this.producerFactory = new DefaultKafkaProducerFactory<>(config, byType(), byType());
        // Explicit destination: spring-kafka 4 defaults to "<topic>-dlt", but the contract and infra use "<topic>.DLT".
        this.recoverer = new DeadLetterPublishingRecoverer(new KafkaTemplate<>(producerFactory),
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, @Nullable Exception exception) {
        recoverer.accept(record, exception);
        log.warn("Dead-lettered {}-{}@{} (key {}) to {}{}", record.topic(), record.partition(), record.offset(),
                record.key(), record.topic(), DLT_SUFFIX, exception);
        if (meterRegistry != null) {
            Counter.builder(DLT_METRIC)
                    .description("Records published to a dead-letter topic")
                    .tag("topic", record.topic())
                    .register(meterRegistry)
                    .increment();
        }
    }

    @Override
    public void destroy() {
        producerFactory.destroy();
    }

    private static Serializer<Object> byType() {
        return new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                String.class, new StringSerializer()));
    }
}
