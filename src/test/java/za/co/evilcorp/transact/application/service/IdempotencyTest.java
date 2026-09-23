package za.co.evilcorp.transact.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.evilcorp.transact.support.AbstractIntegrationTest;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end idempotency: two full ingestAllSources cycles over the same static
 * mock adapter data must not increase the transaction count — the UNIQUE
 * (source_id, source_transaction_id) constraint plus listener duplicate
 * classification absorb the second cycle.
 */
class IdempotencyTest extends AbstractIntegrationTest {

    private static final int EXPECTED_DEMO_TRANSACTIONS = 5;

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private MeterRegistry meterRegistry;

    private long demoTransactionCount() {
        return transactionRepository.findAll().stream()
            .filter(t -> DEMO_CUSTOMER_ID.equals(t.getCustomerId()))
            .count();
    }

    private double duplicateCounterTotal() {
        return meterRegistry.find("transact.ingestion.records.duplicates")
            .counters().stream()
            .mapToDouble(Counter::count)
            .sum();
    }

    @Test
    void secondIngestionCycleDoesNotCreateDuplicateRows() {
        Awaitility.await()
            .atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofMillis(250))
            .until(() -> demoTransactionCount() == EXPECTED_DEMO_TRANSACTIONS);

        long countBefore = demoTransactionCount();
        assertThat(countBefore).isEqualTo(EXPECTED_DEMO_TRANSACTIONS);

        double duplicatesBefore = duplicateCounterTotal();

        ingestionService.ingestAllSources();

        Awaitility.await()
            .atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofMillis(250))
            .until(() -> duplicateCounterTotal() >= duplicatesBefore + EXPECTED_DEMO_TRANSACTIONS);

        List<Long> observedCounts = List.of(
            demoTransactionCount(), demoTransactionCount(), demoTransactionCount());
        assertThat(observedCounts).allSatisfy(c ->
            assertThat(c).isEqualTo(countBefore));
        assertThat(demoTransactionCount()).isEqualTo(EXPECTED_DEMO_TRANSACTIONS);
    }
}
