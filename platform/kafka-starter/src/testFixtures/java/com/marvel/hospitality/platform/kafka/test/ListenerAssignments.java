package com.marvel.hospitality.platform.kafka.test;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Collection;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Test helper for contexts whose Kafka listeners run: waits until every listener container owns all partitions of its
 * topic. A container's consumers join the group one after another and every join rebalances; a retry sequence
 * interrupted by a rebalance restarts its attempt count on the new owner, and a record sent before the assignment
 * settles may be consumed later than a test expects. Tests that count attempts or deliveries call this first
 * (typically from a {@code @BeforeEach}).
 */
public final class ListenerAssignments {

    private ListenerAssignments() {
    }

    /**
     * @param partitionsPerListener the partition count of each listener's topic (3 everywhere, infra/kafka/create-topics.sh)
     * @throws org.awaitility.core.ConditionTimeoutException naming the container that is still short of partitions
     */
    public static void awaitFullyAssigned(KafkaListenerEndpointRegistry registry, int partitionsPerListener,
            Duration timeout) {
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            await("all " + partitionsPerListener + " partitions assigned to listener " + container.getListenerId())
                    .atMost(timeout)
                    .until(() -> assigned(container) == partitionsPerListener);
        }
    }

    private static int assigned(MessageListenerContainer container) {
        Collection<TopicPartition> partitions = container.getAssignedPartitions();
        return partitions == null ? 0 : partitions.size();
    }
}
