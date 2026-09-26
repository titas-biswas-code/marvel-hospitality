package com.marvel.hospitality.payment.application;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.marvel.hospitality.payment.MockJwtDecoderConfiguration;
import com.marvel.hospitality.payment.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link IngestBankTransactionUseCase#ingest} is idempotent on {@code bankTransactionRef} through the ledger's
 * unique-constraint insert (ADR-0014), not a look-up-first: several concurrent deliveries of the same reference must
 * still store the transaction exactly once and write exactly one outbox row, whichever caller's insert wins the race.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockJwtDecoderConfiguration.class})
@ActiveProfiles("test")
class IngestBankTransactionConcurrencyTest {

    private static final int THREAD_COUNT = 4;

    @Autowired
    private IngestBankTransactionUseCase ingestUseCase;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void concurrentDuplicateIngestStoresOneTransactionAndOneOutboxRow() throws Exception {
        String ref = "BANK-TX-" + UUID.randomUUID();
        IngestBankTransactionCommand command = new IngestBankTransactionCommand(
                ref, "NL91ABNA0417164300", "A. Lovelace", new BigDecimal("99.00"), "EUR",
                "concurrent ingest test", Instant.parse("2026-10-01T09:15:00Z"),
                "{\"bankTransactionRef\":\"" + ref + "\"}");

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            List<Callable<IngestResult>> tasks = new ArrayList<>();
            for (int i = 0; i < THREAD_COUNT; i++) {
                tasks.add(() -> {
                    startLatch.await();
                    return ingestUseCase.ingest(command);
                });
            }

            List<Future<IngestResult>> futures = new ArrayList<>();
            for (Callable<IngestResult> task : tasks) {
                futures.add(executor.submit(task));
            }
            startLatch.countDown();

            List<IngestResult> results = new ArrayList<>();
            for (Future<IngestResult> future : futures) {
                results.add(future.get(30, SECONDS));
            }

            assertThat(results).extracting(result -> result.transaction().paymentId())
                    .as("every concurrent caller must see the same winning paymentId")
                    .containsOnly(results.get(0).transaction().paymentId());
            assertThat(results.stream().filter(IngestResult::created).count())
                    .as("exactly one caller must be the one that stored it")
                    .isEqualTo(1L);

            String paymentId = results.get(0).transaction().paymentId().toString();
            assertThat(jdbc.sql("SELECT count(*) FROM bank_transaction WHERE bank_transaction_ref = :ref")
                    .param("ref", ref).query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE aggregate_type = 'payment' AND aggregate_id = :id")
                    .param("id", paymentId).query(Integer.class).single()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
