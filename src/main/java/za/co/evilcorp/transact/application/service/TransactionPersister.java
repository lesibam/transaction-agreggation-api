package za.co.evilcorp.transact.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.Category;
import za.co.evilcorp.transact.domain.service.TransactionCategorizer;
import za.co.evilcorp.transact.infrastructure.persistence.entity.AccountEntity;
import za.co.evilcorp.transact.infrastructure.persistence.entity.IngestionErrorEntity;
import za.co.evilcorp.transact.infrastructure.persistence.entity.TransactionEntity;
import za.co.evilcorp.transact.infrastructure.persistence.repository.AccountRepository;
import za.co.evilcorp.transact.infrastructure.persistence.repository.IngestionErrorRepository;
import za.co.evilcorp.transact.infrastructure.persistence.repository.TransactionRepository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Separate transactional component invoked by the Kafka listener so the
 * @Transactional boundary is entered through the proxy (never self-invoked).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionPersister {

    private static final String SYSTEM_INGESTION = "SYSTEM_INGESTION";
    private static final String ERROR_TYPE_NO_ACCOUNT = "NO_ACCOUNT";
    private static final String ERROR_TYPE_AMBIGUOUS_ACCOUNT = "AMBIGUOUS_ACCOUNT";
    private static final String ERROR_TYPE_INTEGRITY = "INTEGRITY";

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final IngestionErrorRepository ingestionErrorRepository;
    private final TransactionCategorizer categorizer;

    /**
     * Categorizes, resolves the owning account and persists idempotently in one transaction.
     * Unique-constraint violations propagate as DataIntegrityViolationException so the
     * listener can classify duplicate vs. genuine integrity failure OUTSIDE this boundary.
     * Missing or ambiguous account for the source: quarantine (non-retryable) and skip.
     */
    @Transactional
    public void persist(CanonicalTransaction transaction) {
        Category category = categorizer.categorize(transaction);

        List<AccountEntity> accounts =
            accountRepository.findAllBySourceProvider(transaction.getSourceId());

        if (accounts.isEmpty()) {
            quarantine(transaction, ERROR_TYPE_NO_ACCOUNT,
                "No account found for source provider " + transaction.getSourceId());
            log.warn("Quarantined transaction {} from source {}: no account for source provider",
                transaction.getSourceTransactionId(), transaction.getSourceId());
            return;
        }
        if (accounts.size() > 1) {
            quarantine(transaction, ERROR_TYPE_AMBIGUOUS_ACCOUNT,
                "Multiple accounts found for source provider " + transaction.getSourceId());
            log.warn("Quarantined transaction {} from source {}: {} accounts share source provider",
                transaction.getSourceTransactionId(), transaction.getSourceId(), accounts.size());
            return;
        }
        AccountEntity account = accounts.get(0);

        OffsetDateTime now = OffsetDateTime.now();
        TransactionEntity entity = TransactionEntity.builder()
            .customerId(account.getCustomerId())
            .accountId(account.getId())
            .amount(transaction.getAmount())
            .currency(transaction.getCurrency())
            .direction(transaction.getDirection())
            .transactionDate(transaction.getTransactionDate())
            .postedAt(transaction.getPostedAt())
            .description(transaction.getDescription())
            .merchantName(transaction.getMerchantName())
            .categoryCode(category.getCode())
            .categoryVersion(category.getVersion())
            .ruleId(category.getRuleId())
            .normalizationVersion(transaction.getNormalizationVersion())
            .sourceId(transaction.getSourceId())
            .sourceTransactionId(transaction.getSourceTransactionId())
            .ingestedAt(transaction.getIngestedAt() != null ? transaction.getIngestedAt() : now)
            .createdAt(now)
            .updatedAt(now)
            .createdBy(SYSTEM_INGESTION)
            .updatedBy(SYSTEM_INGESTION)
            .build();

        transactionRepository.save(entity);
    }

    /**
     * Quarantines a non-duplicate integrity failure. Called by the listener after the
     * persist transaction has rolled back, so this save runs in its own transaction.
     */
    public void quarantineIntegrity(CanonicalTransaction transaction, String errorMessage) {
        quarantine(transaction, ERROR_TYPE_INTEGRITY, errorMessage);
    }

    private void quarantine(CanonicalTransaction transaction, String errorType, String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        ingestionErrorRepository.save(IngestionErrorEntity.builder()
            .sourceId(transaction.getSourceId())
            .sourceTransactionId(transaction.getSourceTransactionId())
            .errorType(errorType)
            .errorMessage(errorMessage)
            .occurredAt(now)
            .retryable(false)
            .createdAt(now)
            .createdBy(SYSTEM_INGESTION)
            .updatedAt(now)
            .updatedBy(SYSTEM_INGESTION)
            .build());
    }
}
