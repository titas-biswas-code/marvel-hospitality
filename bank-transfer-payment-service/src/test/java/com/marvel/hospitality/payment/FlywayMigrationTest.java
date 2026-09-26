package com.marvel.hospitality.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marvel.hospitality.platform.problem.ConstraintNames;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code V1__schema.sql} against a real Postgres (never H2, ADR-0001): every migration applied cleanly, the schema
 * matches the contract's known constraints, and the check constraint really rejects a non-positive amount rather
 * than only looking correct on paper.
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
                .contains("1");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        List<String> tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN ('bank_transaction', 'refund_instruction', 'outbox_event', 'processed_message')
                        """)
                .query(String.class)
                .list();
        assertThat(tables).containsExactlyInAnyOrder(
                "bank_transaction", "refund_instruction", "outbox_event", "processed_message");

        Integer uniqueConstraintsOnRef = jdbc.sql("""
                        SELECT count(*) FROM pg_constraint c
                        JOIN pg_class t ON t.oid = c.conrelid
                        WHERE t.relname = 'bank_transaction' AND c.contype = 'u'
                        """)
                .query(Integer.class)
                .single();
        assertThat(uniqueConstraintsOnRef).as("unique constraint on bank_transaction_ref").isEqualTo(1);

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO bank_transaction
                            (payment_id, bank_transaction_ref, debtor_account_number, amount, currency,
                             remittance_information, booked_at, received_at, raw)
                        VALUES
                            (:paymentId, :ref, :debtorAccountNumber, :amount, :currency, :info, :bookedAt, :receivedAt,
                             CAST(:raw AS jsonb))
                        """)
                        .param("paymentId", UUID.randomUUID())
                        .param("ref", "FLYWAY-CHECK-" + UUID.randomUUID())
                        .param("debtorAccountNumber", "NL00TEST0000000000")
                        .param("amount", BigDecimal.ZERO.setScale(2))
                        .param("currency", "EUR")
                        .param("info", "flyway migration check-constraint test")
                        .param("bookedAt", OffsetDateTime.now())
                        .param("receivedAt", OffsetDateTime.now())
                        .param("raw", "{}")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(ConstraintNames.sqlState(ex)).contains("23514"));
    }
}
