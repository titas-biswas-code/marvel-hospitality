package com.marvel.hospitality.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.platform.outbox.cdc.SharedContainers;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** The {@code kafka} readiness contributor against the shared broker and against an address nobody listens on. */
class KafkaHealthIndicatorTest {

    @Test
    void reportsUpWithClusterIdWhenBrokerIsReachable() throws Exception {
        KafkaHealthIndicator indicator = new KafkaHealthIndicator(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, SharedContainers.kafka().getBootstrapServers()),
                Duration.ofSeconds(10));
        try {
            Health health = indicator.health(true);

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsKeys("clusterId", "nodes");
        } finally {
            indicator.destroy();
        }
    }

    @Test
    void reportsDownWhenBrokerIsUnreachable() throws Exception {
        KafkaHealthIndicator indicator = new KafkaHealthIndicator(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1"), Duration.ofMillis(500));
        try {
            assertThat(indicator.health(true).getStatus()).isEqualTo(Status.DOWN);
        } finally {
            indicator.destroy();
        }
    }
}
