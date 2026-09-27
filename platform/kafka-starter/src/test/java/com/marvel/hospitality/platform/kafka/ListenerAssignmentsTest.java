package com.marvel.hospitality.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marvel.hospitality.platform.kafka.test.ListenerAssignments;
import com.marvel.hospitality.platform.kafka.testapp.TestApplication;
import java.time.Duration;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * The {@link ListenerAssignments} test fixture services use before counting deliveries, against the real broker.
 * Same annotations as {@link KafkaErrorHandlingTest}, so both share one context and one consumer group.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.application.name=kafka-starter-test-app",
        "spring.kafka.consumer.group-id=kafka-starter-test",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "marvel.kafka.retry.initial-interval=10ms",
        "marvel.kafka.retry.max-interval=40ms"})
@Import(SharedKafkaConfiguration.class)
class ListenerAssignmentsTest {

    @Autowired
    KafkaListenerEndpointRegistry registry;

    @Test
    void returnsOnceEveryListenerOwnsAllPartitionsOfItsTopic() {
        ListenerAssignments.awaitFullyAssigned(registry, 3, Duration.ofSeconds(30));

        assertThat(registry.getListenerContainers()).isNotEmpty().allSatisfy(container ->
                assertThat(container.getAssignedPartitions()).hasSize(3));
    }

    @Test
    void failsNamingTheListenerThatIsShortOfPartitions() {
        ListenerAssignments.awaitFullyAssigned(registry, 3, Duration.ofSeconds(30));
        MessageListenerContainer container = registry.getListenerContainers().iterator().next();

        assertThatThrownBy(() -> ListenerAssignments.awaitFullyAssigned(registry, 4, Duration.ofMillis(300)))
                .isInstanceOf(ConditionTimeoutException.class)
                .hasMessageContaining("all 4 partitions assigned to listener " + container.getListenerId());
    }
}
