package com.marvel.hospitality.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.Status;

/**
 * ADR-0013: the service is ready only with its database and its broker. Runs in the one context that has a real
 * broker, which keeps the production readiness group from application.yml (profile {@code with-broker}).
 */
class ReadinessIntegrationTest extends KafkaListenersIntegrationTest {

    @Autowired
    HealthEndpointGroups groups;

    @Autowired
    HealthEndpoint healthEndpoint;

    @Test
    void readinessIncludesDatabaseAndKafka() {
        var readiness = groups.get("readiness");

        assertThat(readiness).isNotNull();
        assertThat(readiness.isMember("db")).isTrue();
        assertThat(readiness.isMember("kafka")).isTrue();
        assertThat(healthEndpoint.healthForPath("readiness").getStatus()).isEqualTo(Status.UP);
    }
}
