package com.marvel.hospitality.platform.inbox;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records a message as processed in {@code processed_message} (docs/contracts/outbox-and-inbox.md) inside the
 * caller's own transaction (ADR-0006): a consumer calls {@link #markProcessed} first, before applying the message's
 * business effect, in the same transaction as that effect. The insert and the effect then commit or roll back
 * together — a crash between the two could otherwise leave a message marked processed without its effect applied,
 * or the effect applied without the message ever being marked processed, either of which breaks the exactly-once
 * effect that at-least-once Kafka delivery needs on top.
 *
 * <p>Built on {@code INSERT ... ON CONFLICT DO NOTHING RETURNING message_id}: the first delivery of a message
 * inserts a row and gets it back, so {@link #markProcessed} returns {@code true} and the caller applies the effect.
 * A redelivery (consumer restart, rebalance, broker retry) finds the row already there, inserts nothing, and
 * {@link #markProcessed} returns {@code false} — the caller logs at DEBUG and returns without applying the effect a
 * second time. Either way the listener acknowledges the record only after the transaction has committed.
 */
public class ProcessedMessageInbox {

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public ProcessedMessageInbox(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    /**
     * Inserts one {@code processed_message} row and reports whether this call is the first delivery.
     *
     * @param messageId the per-topic business id (docs/contracts/outbox-and-inbox.md lists which field is used, per
     *        topic — e.g. {@code paymentId} for the bank topic, header {@code id} for {@code
     *        reservation-status-changed})
     * @param consumer the consumer group / listener name; together with {@code messageId} this is the table's
     *        primary key, so the same message is tracked independently by each consumer that reads it
     * @param topic the Kafka topic the message arrived on
     * @return {@code true} on first delivery — apply the business effect; {@code false} on a duplicate — skip it
     * @throws IllegalStateException if there is no active transaction — marking the message processed on its own
     *         transaction could let that commit even though the effect it guards rolls back, or the other way
     *         around, which defeats the whole point of the pattern (ADR-0006)
     * @throws IllegalArgumentException if any argument is blank
     */
    public boolean markProcessed(String messageId, String consumer, String topic) {
        requireNonBlank(messageId, "messageId");
        requireNonBlank(consumer, "consumer");
        requireNonBlank(topic, "topic");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "ProcessedMessageInbox.markProcessed() must run inside the caller's own transaction (ADR-0006); "
                            + "no active transaction for message " + messageId + " on topic " + topic);
        }
        return jdbcClient.sql("""
                        INSERT INTO processed_message (message_id, consumer, topic, processed_at)
                        VALUES (:messageId, :consumer, :topic, :processedAt)
                        ON CONFLICT DO NOTHING
                        RETURNING message_id
                        """)
                .param("messageId", messageId)
                .param("consumer", consumer)
                .param("topic", topic)
                .param("processedAt", OffsetDateTime.now(clock))
                .query(String.class)
                .optional()
                .isPresent();
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
