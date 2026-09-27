package com.marvel.hospitality.reservation;

import com.marvel.hospitality.platform.outbox.cdc.DebeziumCdc;
import com.marvel.hospitality.platform.outbox.cdc.SharedContainers;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The JVM-wide Kafka (platform/outbox-starter test fixtures, the same broker the CDC test uses) for contexts that
 * consume. The consumed topics and their DLTs, and {@code refund-requested} (which Debezium publishes to in the refund
 * round-trip test), are created like {@code infra/kafka/create-topics.sh} does (3 partitions) before the listeners
 * start, because the application never creates topics. Only imported by {@link KafkaListenersIntegrationTest}, the one
 * context with {@code spring.kafka.listener.auto-startup=true}; every other test context runs without listeners or a
 * broker.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfiguration {

    public static final String BANK_TOPIC = "bank-transfer-payment-update";
    public static final String BANK_DLT = BANK_TOPIC + ".DLT";
    public static final String REFUND_COMPLETED_TOPIC = "refund-completed";
    public static final String REFUND_COMPLETED_DLT = REFUND_COMPLETED_TOPIC + ".DLT";
    public static final String REFUND_REQUESTED_TOPIC = "refund-requested";

    @Bean(destroyMethod = "")
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        KafkaContainer kafka = SharedContainers.kafka();
        DebeziumCdc.createTopics(
                BANK_TOPIC, BANK_DLT, REFUND_COMPLETED_TOPIC, REFUND_COMPLETED_DLT, REFUND_REQUESTED_TOPIC);
        return kafka;
    }
}
