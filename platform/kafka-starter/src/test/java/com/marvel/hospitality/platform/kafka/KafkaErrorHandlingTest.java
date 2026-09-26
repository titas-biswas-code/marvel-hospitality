package com.marvel.hospitality.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.marvel.hospitality.platform.kafka.testapp.TestApplication;
import com.marvel.hospitality.platform.kafka.testapp.TestListener;
import com.marvel.hospitality.platform.outbox.cdc.DebeziumCdc;
import com.marvel.hospitality.platform.outbox.cdc.SharedContainers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

/**
 * ADR-0008's consumption policy, tested once here against a real broker through a minimal Boot app that picks the
 * starter up like a service does. Retry intervals are shrunk to milliseconds; the attempt count is the real default.
 * Each test uses its own record key, so they share one context and one topic without interfering.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.application.name=kafka-starter-test-app",
        "spring.kafka.consumer.group-id=kafka-starter-test",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "marvel.kafka.retry.initial-interval=10ms",
        "marvel.kafka.retry.max-interval=40ms"})
@Import(SharedKafkaConfiguration.class)
class KafkaErrorHandlingTest {

    private static final String DLT = TestListener.TOPIC + DeadLetterPublisher.DLT_SUFFIX;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    TestListener listener;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    KafkaListenerEndpointRegistry registry;

    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

    @Test
    void listenerContainerAcknowledgesManuallyAndConsumerNeverAutoCommits() {
        MessageListenerContainer container = registry.getListenerContainers().iterator().next();
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(AckMode.MANUAL_IMMEDIATE);
        Map<String, Object> config = consumerFactory.getConfigurationProperties();
        assertThat(config.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
        assertThat(config.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ErrorHandlingDeserializer.class);
        assertThat(config.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ErrorHandlingDeserializer.class);
    }

    @Test
    void validRecordIsProcessedOnce() {
        String key = send("""
                {"behaviour":"ok"}""").key;

        await().atMost(TIMEOUT).until(() -> listener.succeeded(key));
        assertThat(listener.invocations(key)).isEqualTo(1);
    }

    @Test
    void technicalFailureIsRetriedAndSucceedsWithinTheAttempts() {
        String key = send("""
                {"behaviour":"flaky"}""").key;

        await().atMost(TIMEOUT).until(() -> listener.succeeded(key));
        assertThat(listener.invocations(key)).isEqualTo(3);
    }

    @Test
    void retryableFailureIsRetriedWithBackoffThenDeadLettered() {
        Sent sent = send("""
                {"behaviour":"transient"}""");

        ConsumerRecord<String, String> dead = DebeziumCdc.awaitRecord(DLT, sent.key, TIMEOUT);

        assertThat(listener.invocations(sent.key)).isEqualTo(5);
        assertThat(dead.value()).isEqualTo(sent.value);
        assertThat(dead.partition()).as("same partition as the source record").isEqualTo(sent.metadata.partition());
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).isEqualTo(IllegalStateException.class.getName());
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(TestListener.TOPIC);
    }

    @Test
    void nonRetryableFailureBypassesBackoff() {
        Sent sent = send("""
                {"behaviour":"contract"}""");

        ConsumerRecord<String, String> dead = DebeziumCdc.awaitRecord(DLT, sent.key, TIMEOUT);

        assertThat(listener.invocations(sent.key)).isEqualTo(1);
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).isEqualTo(IllegalArgumentException.class.getName());
    }

    @Test
    void malformedJsonIsDeadLetteredWithoutInvokingListener() {
        Sent sent = send("{\"behaviour\": oops");

        ConsumerRecord<String, String> dead = DebeziumCdc.awaitRecord(DLT, sent.key, TIMEOUT);

        assertThat(listener.invocations(sent.key)).isZero();
        assertThat(dead.value()).isEqualTo(sent.value);
        assertThat(header(dead, "kafka_dlt-exception-fqcn")).isNotBlank();
    }

    @Test
    void invalidPayloadIsDeadLetteredWithoutInvokingListener() {
        Sent sent = send("""
                {"behaviour":""}""");

        ConsumerRecord<String, String> dead = DebeziumCdc.awaitRecord(DLT, sent.key, TIMEOUT);

        assertThat(listener.invocations(sent.key)).isZero();
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).contains("MethodArgumentNotValidException");
    }

    @Test
    void deadLetteredRecordIsCommittedAndCounted() throws Exception {
        double before = dltCount();
        Sent sent = send("""
                {"behaviour":"contract"}""");
        DebeziumCdc.awaitRecord(DLT, sent.key, TIMEOUT);

        TopicPartition partition = new TopicPartition(TestListener.TOPIC, sent.metadata.partition());
        await().atMost(TIMEOUT).until(() -> committedOffset(partition) > sent.metadata.offset());
        assertThat(dltCount()).isGreaterThan(before);
    }

    private Sent send(String value) {
        String key = UUID.randomUUID().toString();
        try {
            RecordMetadata metadata = kafkaTemplate.send(TestListener.TOPIC, key, value).get().getRecordMetadata();
            return new Sent(key, value, metadata);
        } catch (Exception e) {
            throw new IllegalStateException("Could not send test record", e);
        }
    }

    private double dltCount() {
        Counter counter = meterRegistry.find(DeadLetterPublisher.DLT_METRIC).tag("topic", TestListener.TOPIC).counter();
        return counter == null ? 0 : counter.count();
    }

    private static long committedOffset(TopicPartition partition) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                SharedContainers.kafka().getBootstrapServers()))) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets("kafka-starter-test")
                    .partitionsToOffsetAndMetadata().get().get(partition);
            return committed == null ? -1 : committed.offset();
        }
    }

    private static @Nullable String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private record Sent(String key, String value, RecordMetadata metadata) {
    }
}
