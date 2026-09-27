package com.marvel.hospitality.notification.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.notification.MockJwtDecoderConfiguration;
import com.marvel.hospitality.notification.TestcontainersConfiguration;
import com.marvel.hospitality.notification.application.NotificationInbox;
import com.marvel.hospitality.platform.kafka.DeadLetterPublisher;
import com.marvel.hospitality.platform.kafka.MarvelKafkaProperties;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves platform/kafka-starter and platform/inbox-starter are wired into this service with this service's own
 * settings (application.yml {@code spring.kafka.*}, ADR-0008). The consumption behaviour itself (blocking retries,
 * DLT routing, ack timing) is exercised once, against a real broker, in platform/kafka-starter's own {@code
 * KafkaErrorHandlingTest}; this class only checks that this service's listener and properties pick it up.
 *
 * <p>Deliberately the exact same annotations as {@code ApplicationContextLoadsTest}/{@code SecurityWiringTest}, so
 * Spring's test context cache reuses their context: the test profile leaves {@code
 * spring.kafka.listener.auto-startup=false}, so this context never needs a running broker either.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class ConsumerWiringTest {

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    @Autowired
    private DefaultErrorHandler errorHandler;

    @Autowired
    private DeadLetterPublisher deadLetterPublisher;

    @Autowired
    private MarvelKafkaProperties kafkaProperties;

    @Autowired
    private NotificationInbox notificationInbox;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void statusListenerUsesPlatformConsumptionPolicy() {
        MessageListenerContainer container = registry.getListenerContainer(ReservationStatusChangedListener.LISTENER_ID);

        assertThat(container).isNotNull();
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(AckMode.MANUAL_IMMEDIATE);
        assertThat(container.getGroupId()).isEqualTo("notification-service");
        assertThat(((ConcurrentMessageListenerContainer<?, ?>) container).getConcurrency()).isEqualTo(3);
        // Only the Kafka integration test context consumes; this one must not even connect (test profile
        // auto-startup=false, and src/test/resources/spring.properties stops paused contexts being restarted).
        assertThat(container.isAutoStartup()).isFalse();
        assertThat(container.isRunning()).isFalse();
    }

    @Test
    void consumerFactoryAppliesThePlatformDeserializationPolicy() {
        Map<String, Object> config = consumerFactory.getConfigurationProperties();

        assertThat(config.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
        assertThat(config.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ErrorHandlingDeserializer.class);
        assertThat(config.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ErrorHandlingDeserializer.class);
        assertThat(config.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
    }

    @Test
    void errorHandlingAndRetryPolicyBeansAreWired() {
        assertThat(errorHandler).isNotNull();
        assertThat(deadLetterPublisher).isNotNull();
        // The test profile only shrinks the waits; the attempt count stays the platform default (ADR-0008).
        assertThat(kafkaProperties.retry().maxAttempts()).isEqualTo(5);
    }

    @Test
    void notificationInboxDeduplicatesWithinTheProcessedMessageTable() {
        UUID eventId = UUID.randomUUID();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        Boolean[] deliveries = transactions.execute(status ->
                new Boolean[] {notificationInbox.firstDelivery(eventId), notificationInbox.firstDelivery(eventId)});

        assertThat(deliveries).containsExactly(true, false);
        // A literal on purpose: the stored consumer name must never change, and must not be the consumer group.
        assertThat(jdbc.sql("SELECT consumer FROM processed_message WHERE message_id = :id")
                .param("id", eventId.toString()).query(String.class).single()).isEqualTo("reservation-status-changed");
    }
}
