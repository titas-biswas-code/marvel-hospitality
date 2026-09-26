package com.marvel.hospitality.reservation;

import com.marvel.hospitality.platform.outbox.cdc.DebeziumCdc;
import com.marvel.hospitality.platform.outbox.cdc.SharedContainers;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The JVM-wide Kafka (platform/outbox-starter test fixtures, the same broker the CDC test uses) for contexts that
 * consume. The bank topic and its DLT are created like {@code infra/kafka/create-topics.sh} does (3 partitions) before
 * the listener starts, because the application never creates topics. Only imported together with
 * {@code spring.kafka.listener.auto-startup=true}; every other test context runs without listeners or a broker.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfiguration {

    public static final String BANK_TOPIC = "bank-transfer-payment-update";
    public static final String BANK_DLT = BANK_TOPIC + ".DLT";

    @Bean(destroyMethod = "")
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        KafkaContainer kafka = SharedContainers.kafka();
        DebeziumCdc.createTopics(BANK_TOPIC, BANK_DLT);
        return kafka;
    }
}
