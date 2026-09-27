package com.marvel.hospitality.payment;

import com.marvel.hospitality.platform.outbox.cdc.DebeziumCdc;
import com.marvel.hospitality.platform.outbox.cdc.SharedContainers;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The JVM-wide Kafka (platform/outbox-starter test fixtures, the same broker the CDC test uses) for contexts that
 * consume. {@code refund-requested} and its DLT are created like {@code infra/kafka/create-topics.sh} does (3
 * partitions) before the listener starts, because the application never creates topics; {@code refund-completed} is
 * created too, since it is only ever produced by Debezium (never by this service directly), and the CDC test reads
 * it with a plain consumer. Only imported together with {@code spring.kafka.listener.auto-startup=true}; every other
 * test context runs without listeners or a broker.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfiguration {

    public static final String REFUND_REQUESTED_TOPIC = "refund-requested";
    public static final String REFUND_REQUESTED_DLT = REFUND_REQUESTED_TOPIC + ".DLT";
    public static final String REFUND_COMPLETED_TOPIC = "refund-completed";

    @Bean(destroyMethod = "")
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        KafkaContainer kafka = SharedContainers.kafka();
        DebeziumCdc.createTopics(REFUND_REQUESTED_TOPIC, REFUND_REQUESTED_DLT, REFUND_COMPLETED_TOPIC);
        return kafka;
    }
}
