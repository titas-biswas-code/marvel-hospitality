package com.marvel.hospitality.platform.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marvel.hospitality.platform.inbox.testapp.TestApplication;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The inbox behaviour every service gets from this starter, tested once here through a minimal Boot app that picks
 * the starter up via its AutoConfiguration.imports. Services only need a wiring test.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.application.name=inbox-test-app",
        "spring.sql.init.mode=always"})
@Import(SharedPostgresConfiguration.class)
class ProcessedMessageInboxTest {

    private static final String TOPIC = "bank-transfer-payment-update";

    @Autowired
    ProcessedMessageInbox inbox;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    void firstDeliveryIsRecorded() {
        String messageId = UUID.randomUUID().toString();

        boolean firstDelivery = inTransaction(() -> inbox.markProcessed(messageId, "reservation-consumer", TOPIC));

        assertThat(firstDelivery).isTrue();
        assertThat(countRows(messageId, "reservation-consumer")).isEqualTo(1L);
    }

    @Test
    void duplicateIsReportedAndNotInsertedTwice() {
        String messageId = UUID.randomUUID().toString();

        boolean first = inTransaction(() -> inbox.markProcessed(messageId, "reservation-consumer", TOPIC));
        boolean second = inTransaction(() -> inbox.markProcessed(messageId, "reservation-consumer", TOPIC));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(countRows(messageId, "reservation-consumer")).isEqualTo(1L);
    }

    @Test
    void sameMessageIdForAnotherConsumerIsIndependent() {
        String messageId = UUID.randomUUID().toString();

        boolean firstConsumer = inTransaction(() -> inbox.markProcessed(messageId, "reservation-consumer", TOPIC));
        boolean secondConsumer = inTransaction(() -> inbox.markProcessed(messageId, "notification-consumer", TOPIC));

        assertThat(firstConsumer).isTrue();
        assertThat(secondConsumer).isTrue();
        assertThat(countRows(messageId, "reservation-consumer")).isEqualTo(1L);
        assertThat(countRows(messageId, "notification-consumer")).isEqualTo(1L);
    }

    @Test
    void refusesToRunOutsideATransaction() {
        String messageId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> inbox.markProcessed(messageId, "reservation-consumer", TOPIC))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rolledBackEffectAlsoRollsBackInboxRow() {
        String messageId = UUID.randomUUID().toString();

        transactionTemplate.execute(status -> {
            boolean firstAttempt = inbox.markProcessed(messageId, "reservation-consumer", TOPIC);
            assertThat(firstAttempt).isTrue();
            status.setRollbackOnly();
            return null;
        });

        assertThat(countRows(messageId, "reservation-consumer")).isZero();

        // The inbox row rolled back with the (simulated) business effect it guarded, so this delivery is "first" again.
        boolean secondAttempt = inTransaction(() -> inbox.markProcessed(messageId, "reservation-consumer", TOPIC));
        assertThat(secondAttempt).isTrue();
    }

    private long countRows(String messageId, String consumer) {
        return jdbcClient.sql("SELECT count(*) FROM processed_message WHERE message_id = :messageId AND consumer = :consumer")
                .param("messageId", messageId)
                .param("consumer", consumer)
                .query(Long.class)
                .single();
    }

    private boolean inTransaction(BooleanSupplier action) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> action.getAsBoolean()));
    }
}
