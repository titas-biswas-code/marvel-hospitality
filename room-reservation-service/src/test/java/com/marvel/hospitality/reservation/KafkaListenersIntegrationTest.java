package com.marvel.hospitality.reservation;

import static org.awaitility.Awaitility.await;

import com.marvel.hospitality.reservation.application.ApplyBankPaymentUseCase;
import com.marvel.hospitality.reservation.application.CompleteRefundUseCase;
import com.marvel.hospitality.reservation.application.RefundPolicy;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The one test context whose Kafka listeners run. Every test class that needs a consuming listener extends this
 * class and declares nothing that changes the context (no own {@code @Import}, properties or bean overrides): test
 * contexts are cached and never paused (src/test/resources/spring.properties), so a second listening context would
 * join the same consumer group and take partitions, and records meant for one test class would be consumed by the
 * other's context.
 *
 * <p>The use cases and the refund policy are spies (plain objects, so a spy wraps the real thing): tests count
 * attempts, inject failures and see what was refunded through them.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=true")
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
public abstract class KafkaListenersIntegrationTest {

    protected static final Duration TIMEOUT = Duration.ofSeconds(30);

    @MockitoSpyBean
    protected ApplyBankPaymentUseCase applyBankPayment;

    @MockitoSpyBean
    protected RefundPolicy refundPolicy;

    @MockitoSpyBean
    protected CompleteRefundUseCase completeRefund;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    /**
     * The three consumer threads of each listener join the group one after another, and each join rebalances. A retry
     * sequence interrupted by a rebalance restarts its attempt count on the new owner, so attempt-counting tests would
     * be flaky. Wait until every listener has all three partitions of its topic assigned first.
     */
    @BeforeEach
    void allPartitionsAreAssigned() {
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            await().atMost(TIMEOUT).until(() -> container.getAssignedPartitions() != null
                    && container.getAssignedPartitions().size() == 3);
        }
    }
}
