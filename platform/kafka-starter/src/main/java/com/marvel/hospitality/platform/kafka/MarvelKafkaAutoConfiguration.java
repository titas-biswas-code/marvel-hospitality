package com.marvel.hospitality.platform.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListenerConfigurer;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.converter.JacksonJsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0008 as beans that Boot's own Kafka auto-configuration picks up for its {@code kafkaListenerContainerFactory}:
 * a {@link CommonErrorHandler}, a {@link RecordMessageConverter}, a {@link ContainerCustomizer} and a
 * {@link DefaultKafkaConsumerFactoryCustomizer}. A service keeps using plain {@code @KafkaListener} and
 * {@code spring.kafka.*} properties; what this starter sets is policy every service shares and is not meant to be
 * switched off per service:
 * <ul>
 *   <li>{@code enable.auto.commit=false} and {@link AckMode#MANUAL_IMMEDIATE}: the listener acknowledges after its
 *       transaction committed, so a crash between poll and commit redelivers instead of losing the record;</li>
 *   <li>key and value read as strings through {@link ErrorHandlingDeserializer}, so bytes that cannot be read reach
 *       the error handler as a {@code DeserializationException} instead of failing the poll loop forever;</li>
 *   <li>JSON turned into the listener's parameter type by {@link JacksonJsonMessageConverter} (Boot's
 *       {@link JsonMapper}); a malformed or invalid ({@code @Valid}) payload fails before the listener method runs;</li>
 *   <li>{@link DefaultErrorHandler}: blocking exponential retries (in order, because per-key order matters), then
 *       {@link DeadLetterPublisher} to {@code <topic>.DLT}. Contract violations skip the retries (see
 *       {@link #NON_RETRYABLE}); business outcomes are never exceptions, so they never reach this path.</li>
 * </ul>
 * Idempotency is the listener's job (inbox starter, same transaction as the effect): with at-least-once delivery a
 * record can arrive twice, e.g. after a rebalance between commit and acknowledgement.
 */
@AutoConfiguration(after = {KafkaAutoConfiguration.class, JacksonAutoConfiguration.class,
        ValidationAutoConfiguration.class})
@ConditionalOnClass(DefaultErrorHandler.class)
@EnableConfigurationProperties(MarvelKafkaProperties.class)
public class MarvelKafkaAutoConfiguration {

    /**
     * Added to spring-kafka's own defaults, which already skip retries for {@code DeserializationException},
     * {@code MessageConversionException}, {@code ConversionException}, {@code MethodArgumentResolutionException}
     * (including {@code MethodArgumentNotValidException} from {@code @Valid}), {@code NoSuchMethodException} and
     * {@code ClassCastException}. {@link IllegalArgumentException} is what value objects throw for a value the
     * contract does not allow (ADR-0008); retrying it would fail identically five times.
     */
    static final Class<? extends Exception>[] NON_RETRYABLE = nonRetryable(IllegalArgumentException.class);

    @Bean
    DefaultKafkaConsumerFactoryCustomizer marvelKafkaConsumerFactoryCustomizer() {
        return consumerFactory -> consumerFactory.updateConfigs(Map.of(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class,
                ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class,
                ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class));
    }

    @Bean
    ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> marvelKafkaContainerCustomizer() {
        return container -> container.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE);
    }

    @Bean
    @ConditionalOnMissingBean(RecordMessageConverter.class)
    @ConditionalOnBean(JsonMapper.class)
    JacksonJsonMessageConverter marvelKafkaMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }

    /** Lets {@code @Valid} on a listener payload use the service's Bean Validation provider. */
    @Bean
    KafkaListenerConfigurer marvelKafkaListenerValidation(ObjectProvider<jakarta.validation.Validator> validator) {
        return registrar -> validator.ifUnique(v -> registrar.setValidator(new SpringValidatorAdapter(v)));
    }

    @Bean
    @ConditionalOnBean(ProducerFactory.class)
    @ConditionalOnMissingBean
    DeadLetterPublisher deadLetterPublisher(ProducerFactory<?, ?> producerFactory, ObjectProvider<MeterRegistry> meterRegistry) {
        return new DeadLetterPublisher(producerFactory, meterRegistry.getIfAvailable());
    }

    @Bean
    @ConditionalOnBean(DeadLetterPublisher.class)
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler marvelKafkaErrorHandler(DeadLetterPublisher deadLetterPublisher, MarvelKafkaProperties properties) {
        MarvelKafkaProperties.Retry retry = properties.retry();
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxAttempts() - 1);
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterPublisher, backOff);
        errorHandler.addNotRetryableExceptions(NON_RETRYABLE);
        // With manual acknowledgement the container would otherwise leave a dead-lettered record's offset
        // uncommitted, and a restart would dead-letter it again.
        errorHandler.setCommitRecovered(true);
        return errorHandler;
    }

    @SafeVarargs
    private static Class<? extends Exception>[] nonRetryable(Class<? extends Exception>... types) {
        return types;
    }
}
