package com.marvel.hospitality.platform.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The wiring rules of {@link MarvelInboxAutoConfiguration}, isolated from any real database: a mocked
 * {@link JdbcClient} is enough to prove the bean graph, since {@link ProcessedMessageInbox} itself is exercised
 * against real Postgres in {@link ProcessedMessageInboxTest}.
 */
class MarvelInboxAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MarvelInboxAutoConfiguration.class));

    @Test
    void registersProcessedMessageInboxWhenAJdbcClientExists() {
        contextRunner
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .run(context -> assertThat(context).hasSingleBean(ProcessedMessageInbox.class));
    }

    @Test
    void leavesProcessedMessageInboxOutWithoutAJdbcClient() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(ProcessedMessageInbox.class));
    }

    @Test
    void userDefinedProcessedMessageInboxWinsOverTheStartersOwn() {
        contextRunner
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withUserConfiguration(CustomInboxConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ProcessedMessageInbox.class);
                    assertThat(context.getBean(ProcessedMessageInbox.class))
                            .isSameAs(CustomInboxConfiguration.CUSTOM_INBOX);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomInboxConfiguration {

        static final ProcessedMessageInbox CUSTOM_INBOX =
                new ProcessedMessageInbox(mock(JdbcClient.class), Clock.systemUTC());

        @Bean
        ProcessedMessageInbox processedMessageInbox() {
            return CUSTOM_INBOX;
        }
    }
}
