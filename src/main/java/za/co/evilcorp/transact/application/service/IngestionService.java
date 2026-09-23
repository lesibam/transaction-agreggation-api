package za.co.evilcorp.transact.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import za.co.evilcorp.transact.application.port.TransactionEventPublisher;
import za.co.evilcorp.transact.application.port.TransactionIngestedEvent;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.infrastructure.persistence.entity.IngestionErrorEntity;
import za.co.evilcorp.transact.infrastructure.persistence.entity.SourceSyncStateEntity;
import za.co.evilcorp.transact.infrastructure.persistence.repository.IngestionErrorRepository;
import za.co.evilcorp.transact.infrastructure.persistence.repository.SourceSyncStateRepository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Service
public class IngestionService {

    private static final String ERROR_TYPE_VALIDATION = "VALIDATION";
    private static final String SYSTEM_INGESTION = "SYSTEM_INGESTION";

    private final List<TransactionSource> sources;
    private final SourceSyncStateRepository syncStateRepository;
    private final TransactionEventPublisher eventPublisher;
    private final IngestionErrorRepository ingestionErrorRepository;
    private final MeterRegistry meterRegistry;
    private final int retryMaxAttempts;
    private final long retryBackoffMs;

    public IngestionService(
            List<TransactionSource> sources,
            SourceSyncStateRepository syncStateRepository,
            TransactionEventPublisher eventPublisher,
            IngestionErrorRepository ingestionErrorRepository,
            MeterRegistry meterRegistry,
            @Value("${app.ingestion.retry-max-attempts:3}") int retryMaxAttempts,
            @Value("${app.ingestion.retry-backoff-ms:500}") long retryBackoffMs) {
        this.sources = sources;
        this.syncStateRepository = syncStateRepository;
        this.eventPublisher = eventPublisher;
        this.ingestionErrorRepository = ingestionErrorRepository;
        this.meterRegistry = meterRegistry;
        this.retryMaxAttempts = retryMaxAttempts;
        this.retryBackoffMs = retryBackoffMs;
    }

    public void ingestAllSources() {
        log.info("Starting global ingestion process...");
        for (TransactionSource source : sources) {
            try {
                ingestSource(source);
            } catch (Exception e) {
                log.error("Critical failure during ingestion for source {}: {}", source.getSourceId(), e.getMessage());
            }
        }
        log.info("Global ingestion process completed.");
    }

    private void ingestSource(TransactionSource source) {
        String sourceId = source.getSourceId();
        meterRegistry.timer("transact.ingestion.duration", "source", sourceId)
            .record(() -> runSourceSync(source, sourceId));
    }

    private void runSourceSync(TransactionSource source, String sourceId) {
        log.info("Processing source: {}", sourceId);

        SourceSyncStateEntity state = null;
        try {
            state = syncStateRepository.findById(sourceId)
                .orElseGet(() -> createInitialState(sourceId));

            final SourceSyncStateEntity currentState = state;
            TransactionSource.SourceResponse response = withRetry(sourceId, "fetch",
                () -> source.fetchTransactions(Optional.ofNullable(currentState.getCursor())));

            List<CanonicalTransaction> records = response.transactions() == null
                ? List.of()
                : response.transactions();
            meterRegistry.counter("transact.ingestion.records.received", "source", sourceId)
                .increment(records.size());

            int quarantined = 0;
            int published = 0;
            for (CanonicalTransaction record : records) {
                if (!isValid(record)) {
                    quarantineValidationFailure(sourceId, record);
                    quarantined++;
                    continue;
                }
                TransactionIngestedEvent event = new TransactionIngestedEvent(
                    UUID.randomUUID(), OffsetDateTime.now(), sourceId, record);
                withRetry(sourceId, "publish", () -> {
                    eventPublisher.publish(event);
                    return null;
                });
                published++;
                meterRegistry.counter("transact.ingestion.records.published", "source", sourceId).increment();
            }

            markSyncSuccess(currentState, response.nextCursor());
            meterRegistry.counter("transact.ingestion.source.sync.success", "source", sourceId).increment();
            log.info("Source {} sync completed. Published: {}, Quarantined: {}",
                sourceId, published, quarantined);
        } catch (Exception e) {
            if (state != null) {
                markSyncFailure(state, e);
            }
            meterRegistry.counter("transact.ingestion.source.sync.failure", "source", sourceId).increment();
            log.error("Source {} sync failed after retries: {}", sourceId, e.getMessage());
        }
    }

    /**
     * Defensive per-record validation. Adapters normalize upstream, but any record
     * with a missing mandatory field is quarantined (non-retryable) and the batch continues.
     */
    private boolean isValid(CanonicalTransaction record) {
        return record != null
            && record.getAmount() != null
            && record.getCurrency() != null
            && record.getSourceTransactionId() != null
            && record.getDirection() != null
            && record.getTransactionDate() != null;
    }

    private void quarantineValidationFailure(String sourceId, CanonicalTransaction record) {
        String sourceTransactionId =
            record != null && record.getSourceTransactionId() != null
                ? record.getSourceTransactionId()
                : "UNKNOWN";

        List<String> missing = new ArrayList<>();
        if (record == null) {
            missing.add("record");
        } else {
            if (record.getAmount() == null) missing.add("amount");
            if (record.getCurrency() == null) missing.add("currency");
            if (record.getSourceTransactionId() == null) missing.add("sourceTransactionId");
            if (record.getDirection() == null) missing.add("direction");
            if (record.getTransactionDate() == null) missing.add("transactionDate");
        }
        String message = "Validation failed, missing required field(s): " + String.join(", ", missing);

        quarantine(sourceId, sourceTransactionId, ERROR_TYPE_VALIDATION, message);
        meterRegistry.counter("transact.ingestion.records.quarantined", "source", sourceId).increment();
        log.warn("Quarantined malformed record from source {} (sourceTransactionId={}): {}",
            sourceId, sourceTransactionId, message);
    }

    private void quarantine(String sourceId, String sourceTransactionId,
                            String errorType, String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        ingestionErrorRepository.save(IngestionErrorEntity.builder()
            .sourceId(sourceId)
            .sourceTransactionId(sourceTransactionId)
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

    /**
     * Bounded retry with exponential backoff for fetch/publish infrastructure failures.
     * Validation problems never reach here (they are quarantined, not thrown).
     */
    private <T> T withRetry(String sourceId, String operation, Supplier<T> action) {
        long backoffMs = retryBackoffMs;
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Ingestion '{}' attempt {}/{} failed for source {}: {}",
                    operation, attempt, retryMaxAttempts, sourceId, e.getMessage());
                if (attempt < retryMaxAttempts) {
                    sleep(backoffMs);
                    backoffMs = backoffMs * 2;
                }
            }
        }
        throw new IngestionOperationException(sourceId, operation, retryMaxAttempts, lastFailure);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestionOperationException("interrupted", "retry-backoff", 1, e);
        }
    }

    private SourceSyncStateEntity createInitialState(String sourceId) {
        OffsetDateTime now = OffsetDateTime.now();
        return syncStateRepository.save(SourceSyncStateEntity.builder()
            .sourceId(sourceId)
            .status("INITIAL")
            .failureCount(0)
            .createdAt(now)
            .createdBy(SYSTEM_INGESTION)
            .updatedAt(now)
            .updatedBy(SYSTEM_INGESTION)
            .build());
    }

    private void markSyncSuccess(SourceSyncStateEntity state, String nextCursor) {
        OffsetDateTime now = OffsetDateTime.now();
        state.setCursor(nextCursor);
        state.setLastSuccessfulSync(now);
        state.setLastAttemptedSync(now);
        state.setStatus("SUCCESS");
        state.setFailureCount(0);
        state.setUpdatedAt(now);
        state.setUpdatedBy(SYSTEM_INGESTION);
        syncStateRepository.save(state);
    }

    private void markSyncFailure(SourceSyncStateEntity state, Exception e) {
        OffsetDateTime now = OffsetDateTime.now();
        String message = e.getMessage() != null ? e.getMessage() : e.toString();
        state.setStatus("FAILED");
        state.setFailureCount((state.getFailureCount() == null ? 0 : state.getFailureCount()) + 1);
        state.setLastError(message);
        state.setLastAttemptedSync(now);
        state.setUpdatedAt(now);
        state.setUpdatedBy(SYSTEM_INGESTION);
        syncStateRepository.save(state);
    }

    private static final class IngestionOperationException extends RuntimeException {
        private IngestionOperationException(String sourceId, String operation, int attempts, Throwable cause) {
            super(operation + " failed for source " + sourceId + " after " + attempts + " attempt(s): "
                + (cause.getMessage() != null ? cause.getMessage() : cause.toString()), cause);
        }
    }
}
