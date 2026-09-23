package za.co.evilcorp.transact.application.service;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.evilcorp.transact.infrastructure.persistence.entity.SourceSyncStateEntity;
import za.co.evilcorp.transact.infrastructure.persistence.entity.TransactionEntity;
import za.co.evilcorp.transact.infrastructure.persistence.repository.SourceSyncStateRepository;
import za.co.evilcorp.transact.support.AbstractIntegrationTest;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full pipeline happy path: adapters → validate → Kafka → listener → persist.
 * Rows appear asynchronously, so every assertion is wrapped in Awaitility polls.
 */
class IngestionServiceTest extends AbstractIntegrationTest {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private SourceSyncStateRepository sourceSyncStateRepository;

    private List<TransactionEntity> demoCustomerTransactions() {
        return transactionRepository.findAll().stream()
            .filter(t -> DEMO_CUSTOMER_ID.equals(t.getCustomerId()))
            .toList();
    }

    @Test
    void shouldIngestCategorizeAndPersistFromAllMockSources() {
        ingestionService.ingestAllSources();

        Awaitility.await()
            .atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofMillis(250))
            .until(() -> {
                List<TransactionEntity> rows = demoCustomerTransactions();
                return rows.size() >= 5
                    && rows.stream().allMatch(t -> t.getCategoryCode() != null);
            });

        List<TransactionEntity> transactions = demoCustomerTransactions();
        assertThat(transactions).isNotEmpty();
        assertThat(transactions).allMatch(t -> t.getCategoryCode() != null);
        assertThat(transactions).allMatch(t -> t.getRuleId() != null);
        assertThat(transactions)
            .extracting(TransactionEntity::getCategoryCode)
            .contains("GROCERIES", "FOOD_DELIVERY", "ENTERTAINMENT");
        assertThat(transactions)
            .extracting(TransactionEntity::getSourceId)
            .contains("SOURCE_A", "SOURCE_B", "SOURCE_C");
    }

    @Test
    void allSourcesEndInSuccessSyncStateAfterIngestion() {
        ingestionService.ingestAllSources();

        for (String sourceId : List.of("SOURCE_A", "SOURCE_B", "SOURCE_C")) {
            Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    SourceSyncStateEntity state =
                        sourceSyncStateRepository.findById(sourceId).orElse(null);
                    assertThat(state).isNotNull();
                    assertThat(state.getStatus()).isEqualTo("SUCCESS");
                    assertThat(state.getLastSuccessfulSync()).isNotNull();
                    assertThat(state.getFailureCount()).isZero();
                });
        }
    }
}
