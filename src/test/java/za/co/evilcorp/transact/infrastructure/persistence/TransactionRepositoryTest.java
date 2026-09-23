package za.co.evilcorp.transact.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import za.co.evilcorp.transact.application.dto.CurrencySummaryRow;
import za.co.evilcorp.transact.domain.model.AccountType;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.infrastructure.persistence.entity.AccountEntity;
import za.co.evilcorp.transact.infrastructure.persistence.entity.CustomerEntity;
import za.co.evilcorp.transact.infrastructure.persistence.entity.TransactionEntity;
import za.co.evilcorp.transact.infrastructure.persistence.repository.AccountRepository;
import za.co.evilcorp.transact.infrastructure.persistence.repository.CustomerRepository;
import za.co.evilcorp.transact.infrastructure.persistence.repository.TransactionRepository;
import za.co.evilcorp.transact.support.SharedContainers;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Repository-level tests. Uses @SpringBootTest + @Transactional: every test
 * rolls back, and all assertions are scoped to the test customer because the
 * shared DB also holds demo-seed and pipeline-ingested rows.
 */
@SpringBootTest
@Transactional
class TransactionRepositoryTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedContainers.postgres().getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedContainers.postgres().getUsername());
        registry.add("spring.datasource.password", () -> SharedContainers.postgres().getPassword());
        registry.add("spring.kafka.bootstrap-servers", () -> SharedContainers.kafka().getBootstrapServers());
    }

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountRepository accountRepository;

    private UUID customerId;
    private UUID accountId;

    @BeforeEach
    void seedCustomerAndAccount() {
        OffsetDateTime now = OffsetDateTime.now();

        CustomerEntity customer = customerRepository.save(CustomerEntity.builder()
            .externalId("ext-" + UUID.randomUUID())
            .name("Test Customer")
            .email(UUID.randomUUID() + "@transact.local")
            .tenantId("tenant-1")
            .createdAt(now)
            .updatedAt(now)
            .createdBy("TEST")
            .updatedBy("TEST")
            .build());
        customerId = customer.getId();

        AccountEntity account = accountRepository.save(AccountEntity.builder()
            .customerId(customerId)
            .accountType(AccountType.CHECKING)
            .currency("ZAR")
            .sourceProvider("SOURCE_A")
            .createdAt(now)
            .updatedAt(now)
            .createdBy("TEST")
            .updatedBy("TEST")
            .build());
        accountId = account.getId();
    }

    private List<TransactionEntity> rowsForTestCustomer() {
        return transactionRepository.searchByCustomerKeyset(
            customerId, null, null, null, null, null, null, null, null, 1000);
    }

    @Test
    void shouldPreventDuplicateIngestionFromSameSource() {
        String sourceId = "SOURCE_A";
        String txId = "TXN_123";

        transactionRepository.save(newTransaction(sourceId, txId));

        TransactionEntity duplicate = newTransaction(sourceId, txId);

        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> {
            transactionRepository.saveAndFlush(duplicate);
        });

        assertThat(isUniqueSourceTransactionViolation(ex))
            .as("expected unique violation on idx_unique_source_transaction (SQLState 23505), but got: %s",
                fullMessage(ex))
            .isTrue();
    }

    @Test
    void shouldAllowSameTransactionIdFromDifferentSources() {
        String txId = "TXN_" + UUID.randomUUID();

        transactionRepository.save(newTransaction("SOURCE_A", txId));
        transactionRepository.save(newTransaction("SOURCE_B", txId));

        assertThat(rowsForTestCustomer()).hasSize(2);
    }

    @Test
    void keysetPaginationReturnsStableOrderedPagesWithoutDuplicates() {
        OffsetDateTime base = OffsetDateTime.parse("2026-01-10T12:00:00Z");
        int total = 12;
        for (int i = 0; i < total; i++) {
            transactionRepository.saveAndFlush(
                newTransactionAt("SOURCE_A", "KT-" + i + "-" + UUID.randomUUID(), base.minusHours(i)));
        }

        List<UUID> visitedIds = new ArrayList<>();
        OffsetDateTime beforeDate = null;
        UUID beforeId = null;
        int pages = 0;

        while (true) {
            List<TransactionEntity> page = transactionRepository.searchByCustomerKeyset(
                customerId, null, null, null, null, null, null,
                beforeDate, beforeId, 5);
            if (page.isEmpty()) {
                break;
            }
            pages++;

            for (int i = 1; i < page.size(); i++) {
                TransactionEntity prev = page.get(i - 1);
                TransactionEntity cur = page.get(i);
                int dateOrder = prev.getTransactionDate().compareTo(cur.getTransactionDate());
                assertThat(dateOrder).isGreaterThanOrEqualTo(0);
                if (dateOrder == 0) {
                    assertThat(prev.getId().compareTo(cur.getId())).isGreaterThan(0);
                }
            }

            for (TransactionEntity row : page) {
                assertThat(visitedIds.add(row.getId()))
                    .as("duplicate row across keyset pages: %s", row.getId())
                    .isTrue();
            }

            TransactionEntity last = page.get(page.size() - 1);
            beforeDate = last.getTransactionDate();
            beforeId = last.getId();

            if (page.size() < 5) {
                break;
            }
            assertThat(pages).isLessThan(10);
        }

        assertThat(visitedIds).hasSize(total);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void summaryAggregatesSumsPerCurrencyWithoutMerging() {
        OffsetDateTime now = OffsetDateTime.now();
        transactionRepository.saveAndFlush(
            newTransactionFull("SOURCE_A", "SUM-1", now.minusDays(1),
                TransactionDirection.DEBIT, new BigDecimal("100.00"), "ZAR", "GROCERIES"));
        transactionRepository.saveAndFlush(
            newTransactionFull("SOURCE_A", "SUM-2", now.minusDays(1),
                TransactionDirection.DEBIT, new BigDecimal("50.00"), "ZAR", "FOOD_DELIVERY"));
        transactionRepository.saveAndFlush(
            newTransactionFull("SOURCE_A", "SUM-3", now.minusDays(1),
                TransactionDirection.CREDIT, new BigDecimal("200.00"), "ZAR", "INCOME"));
        transactionRepository.saveAndFlush(
            newTransactionFull("SOURCE_A", "SUM-4", now.minusDays(1),
                TransactionDirection.DEBIT, new BigDecimal("10.00"), "USD", "ENTERTAINMENT"));

        List<CurrencySummaryRow> rows = transactionRepository.summarizeByCurrency(customerId, null, null);

        assertThat(rows).hasSize(2);

        CurrencySummaryRow zar = rows.stream()
            .filter(r -> "ZAR".equals(r.getCurrency()))
            .findFirst()
            .orElseThrow();
        assertThat(zar.getTotalDebit()).isEqualByComparingTo("150.00");
        assertThat(zar.getTotalCredit()).isEqualByComparingTo("200.00");

        CurrencySummaryRow usd = rows.stream()
            .filter(r -> "USD".equals(r.getCurrency()))
            .findFirst()
            .orElseThrow();
        assertThat(usd.getTotalDebit()).isEqualByComparingTo("10.00");
        assertThat(usd.getTotalCredit()).isEqualByComparingTo("0");
    }

    @Test
    void summaryRespectsDateWindow() {
        OffsetDateTime base = OffsetDateTime.parse("2026-02-01T00:00:00Z");
        transactionRepository.saveAndFlush(
            newTransactionFull("SOURCE_A", "WIN-1", base.plusHours(1),
                TransactionDirection.DEBIT, new BigDecimal("10.00"), "ZAR", "GROCERIES"));
        transactionRepository.saveAndFlush(
            newTransactionFull("SOURCE_A", "WIN-2", base.plusDays(10),
                TransactionDirection.DEBIT, new BigDecimal("20.00"), "ZAR", "GROCERIES"));

        List<CurrencySummaryRow> rows = transactionRepository.summarizeByCurrency(
            customerId, base, base.plusDays(1));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTotalDebit()).isEqualByComparingTo("10.00");
    }

    private TransactionEntity newTransaction(String sourceId, String sourceTransactionId) {
        return newTransactionFull(sourceId, sourceTransactionId, OffsetDateTime.now(),
            TransactionDirection.DEBIT, BigDecimal.TEN, "ZAR", "GROCERIES");
    }

    private TransactionEntity newTransactionAt(String sourceId, String sourceTransactionId,
                                               OffsetDateTime transactionDate) {
        return newTransactionFull(sourceId, sourceTransactionId, transactionDate,
            TransactionDirection.DEBIT, BigDecimal.TEN, "ZAR", "GROCERIES");
    }

    private TransactionEntity newTransactionFull(String sourceId, String sourceTransactionId,
                                                 OffsetDateTime transactionDate,
                                                 TransactionDirection direction,
                                                 BigDecimal amount, String currency,
                                                 String categoryCode) {
        OffsetDateTime now = OffsetDateTime.now();
        return TransactionEntity.builder()
            .customerId(customerId)
            .accountId(accountId)
            .normalizationVersion("1")
            .amount(amount)
            .currency(currency)
            .direction(direction)
            .transactionDate(transactionDate)
            .categoryCode(categoryCode)
            .sourceId(sourceId)
            .sourceTransactionId(sourceTransactionId)
            .ingestedAt(now)
            .createdAt(now)
            .updatedAt(now)
            .createdBy("TEST")
            .updatedBy("TEST")
            .build();
    }

    private static boolean isUniqueSourceTransactionViolation(Throwable ex) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sqle && "23505".equals(sqle.getSQLState())) {
                return true;
            }
            if (t.getMessage() != null) {
                messages.append(t.getMessage()).append('\n');
            }
        }
        String all = messages.toString();
        return all.contains("duplicate key") || all.contains("idx_unique_source_transaction");
    }

    private static String fullMessage(Throwable ex) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                messages.append(t.getMessage()).append(" | ");
            }
        }
        return messages.toString();
    }
}
