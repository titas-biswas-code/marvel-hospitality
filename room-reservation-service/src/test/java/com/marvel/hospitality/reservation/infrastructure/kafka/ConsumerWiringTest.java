package com.marvel.hospitality.reservation.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.platform.inbox.ProcessedMessageInbox;
import com.marvel.hospitality.platform.kafka.DeadLetterPublisher;
import com.marvel.hospitality.platform.kafka.MarvelKafkaProperties;
import com.marvel.hospitality.reservation.MockJwtDecoderConfiguration;
import com.marvel.hospitality.reservation.TestcontainersConfiguration;
import com.marvel.hospitality.reservation.application.PaymentInbox;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
 * settings (application.yml {@code spring.kafka.*}, ADR-0008, ADR-0009) — the same kind of wiring test
 * {@link com.marvel.hospitality.reservation.SecurityWiringTest} is for platform/security-starter. The consumption
 * behaviour itself (blocking retries, DLT routing, ack timing) is exercised once, against a real broker, in
 * platform/kafka-starter's own {@code KafkaErrorHandlingTest}; this class only checks that this service's listener
 * and properties actually pick that behaviour up.
 *
 * <p>Deliberately the exact same annotations as {@code ApplicationContextLoadsTest}/{@code SecurityWiringTest} (down
 * to import order), so Spring's test context cache reuses their context and this JVM does not have to boot a second
 * one: the test profile leaves {@code spring.kafka.listener.auto-startup=false}, so this context never needs a
 * running broker either.
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
    private ProcessedMessageInbox processedMessageInbox;

    @Autowired
    private PaymentInbox paymentInbox;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void bankPaymentListenerUsesPlatformConsumptionPolicy() {
        MessageListenerContainer container = registry.getListenerContainer(BankTransferPaymentUpdateListener.LISTENER_ID);

        assertThat(container).isNotNull();
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(AckMode.MANUAL_IMMEDIATE);
        assertThat(container.getGroupId()).isEqualTo("room-reservation-service");
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
        // application.yml's test profile only shrinks marvel.kafka.retry's initial/max interval to milliseconds;
        // the attempt count is left at the platform default (ADR-0008), so it stays the production value here.
        assertThat(kafkaProperties.retry().maxAttempts()).isEqualTo(5);
    }

    @Test
    void paymentInboxDeduplicatesWithinTheProcessedMessageTable() {
        assertThat(processedMessageInbox).isNotNull();
        String paymentId = UUID.randomUUID().toString();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        // Both calls inside one transaction, like a real consumer would call it once per delivery attempt within the
        // same business transaction: the first insert wins, the second finds the row already there.
        Boolean[] deliveries = transactions.execute(status ->
                new Boolean[] {paymentInbox.firstDelivery(paymentId), paymentInbox.firstDelivery(paymentId)});

        assertThat(deliveries).containsExactly(true, false);
    }
}
