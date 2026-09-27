package com.marvel.hospitality.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The notification schema is database-schemas.md's, including what Hibernate's {@code ddl-auto: validate} cannot
 * see: the unique {@code event_id} and the index {@code GET /notifications} reads through. Same cached context as the
 * other non-listening tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void flywayMigratesCleanly() {
        assertThat(flyway.info().applied())
                .isNotEmpty()
                .allSatisfy(migration -> assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("1", "2");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name IN ('notification', 'processed_message')
                        """)
                .query(String.class).list())
                .containsExactlyInAnyOrder("notification", "processed_message");
    }

    @Test
    void notificationHasUniqueEventIdAndReservationIndex() {
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE tablename = 'notification'")
                .query(String.class).list())
                .contains("CREATE INDEX notification_reservation_idx ON public.notification USING btree "
                        + "(reservation_id, created_at)")
                .anySatisfy(definition -> assertThat(definition).startsWith("CREATE UNIQUE INDEX")
                        .endsWith("(event_id)"));
    }
}
