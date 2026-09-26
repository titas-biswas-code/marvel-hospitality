package com.marvel.hospitality.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.CommonLoggingErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.JacksonJsonMessageConverter;

/** Which beans the starter contributes and when it backs off; no broker needed (nothing connects on startup). */
class MarvelKafkaAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, KafkaAutoConfiguration.class, MarvelKafkaAutoConfiguration.class));

    @Test
    void contributesErrorHandlerDeadLetterPublisherAndJsonConverter() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DefaultErrorHandler.class);
            assertThat(context).hasSingleBean(DeadLetterPublisher.class);
            assertThat(context).hasSingleBean(JacksonJsonMessageConverter.class);
        });
    }

    @Test
    void serviceDefinedErrorHandlerWins() {
        runner.withBean(CommonErrorHandler.class, CommonLoggingErrorHandler::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(CommonErrorHandler.class);
                    assertThat(context).doesNotHaveBean(DefaultErrorHandler.class);
                });
    }

    @Test
    void rejectsRetryPolicyWithoutAnyAttempt() {
        runner.withPropertyValues("marvel.kafka.retry.max-attempts=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
