package com.marvel.hospitality.platform.kafka;

import com.marvel.hospitality.platform.kafka.testapp.TestListener;
import com.marvel.hospitality.platform.outbox.cdc.DebeziumCdc;
import com.marvel.hospitality.platform.outbox.cdc.SharedContainers;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The JVM-wide Kafka from the outbox starter's test fixtures. The topic and its DLT are created (3 partitions, like
 * infra) before the listener starts, so the broker never auto-creates them with a different layout.
 * {@code destroyMethod = ""}: the container outlives the context (Ryuk removes it at JVM exit).
 */
@TestConfiguration(proxyBeanMethods = false)
class SharedKafkaConfiguration {

    @Bean(destroyMethod = "")
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        KafkaContainer kafka = SharedContainers.kafka();
        DebeziumCdc.createTopics(TestListener.TOPIC, TestListener.TOPIC + DeadLetterPublisher.DLT_SUFFIX);
        return kafka;
    }
}
