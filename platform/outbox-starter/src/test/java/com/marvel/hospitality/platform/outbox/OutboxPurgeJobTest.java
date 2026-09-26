package com.marvel.hospitality.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.marvel.hospitality.platform.outbox.testapp.TestApplication;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The purge every service gets from this starter (docs/contracts/outbox-and-inbox.md), tested once here. Same
 * context as {@link OutboxEventWriterTest}, so both share one Spring context and one Postgres; rows are told apart
 * by aggregate id, never by global counts.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.application.name=outbox-test-app",
        "spring.sql.init.mode=always"})
@Import(SharedPostgresConfiguration.class)
class OutboxPurgeJobTest {

    /** TestApplication's fixed clock is 2026-09-26T10:15:30Z, so the 7-day cutoff is 2026-09-19T10:15:30Z. */
    private static final OffsetDateTime CUTOFF = OffsetDateTime.parse("2026-09-19T10:15:30Z");

    @Autowired
    OutboxPurgeJob purgeJob;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void purgesOnlyRowsOlderThanRetention() {
        String prefix = "purge-" + UUID.randomUUID();
        insertRow(prefix + "-old", CUTOFF.minusSeconds(1));
        insertRow(prefix + "-at-cutoff", CUTOFF);
        insertRow(prefix + "-new", CUTOFF.plusDays(6));

        int deleted = purgeJob.purge();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(jdbcClient.sql("SELECT aggregate_id FROM outbox_event WHERE aggregate_id LIKE :prefix ORDER BY aggregate_id")
                .param("prefix", prefix + "%")
                .query(String.class)
                .list())
                .containsExactly(prefix + "-at-cutoff", prefix + "-new");
    }

    @Test
    void purgeJobIsNotRegisteredWhenDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, MarvelOutboxAutoConfiguration.class))
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withPropertyValues("spring.application.name=outbox-test-app", "marvel.outbox.purge.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxEventWriter.class);
                    assertThat(context).doesNotHaveBean(OutboxPurgeJob.class);
                });
    }

    private void insertRow(String aggregateId, OffsetDateTime createdAt) {
        jdbcClient.sql("""
                        INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, topic, producer, payload, created_at)
                        VALUES (:id, 'test', :aggregateId, 'TestEvent', 'test-topic', 'outbox-test-app', '{}'::jsonb, :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("aggregateId", aggregateId)
                .param("createdAt", createdAt)
                .update();
    }
}
