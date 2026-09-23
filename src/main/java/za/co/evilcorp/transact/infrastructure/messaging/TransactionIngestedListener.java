package za.co.evilcorp.transact.infrastructure.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import za.co.evilcorp.transact.application.port.TransactionIngestedEvent;
import za.co.evilcorp.transact.application.service.TransactionPersister;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;

import java.sql.SQLException;

/**
 * Consumes TransactionIngestedEvent, delegates the transactional persist to
 * TransactionPersister (separate bean — no self-invoked @Transactional), and
 * classifies DataIntegrityViolationException: true duplicate vs. genuine failure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionIngestedListener {

    private final TransactionPersister transactionPersister;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
        topics = "transactions.ingested",
        groupId = "transact-ingestion",
        containerFactory = "transactionIngestedContainerFactory")
    public void onTransactionIngested(TransactionIngestedEvent event) {
        if (event == null || event.transaction() == null) {
            // Poison message: let the error handler retry then dead-letter it.
            throw new IllegalArgumentException("TransactionIngestedEvent or its transaction payload is null");
        }

        CanonicalTransaction transaction = event.transaction();
        String source = event.source();
        try {
            transactionPersister.persist(transaction);
        } catch (DataIntegrityViolationException e) {
            handleIntegrityViolation(source, transaction, e);
        }
    }

    private void handleIntegrityViolation(String source, CanonicalTransaction transaction,
                                          DataIntegrityViolationException e) {
        if (isDuplicate(e)) {
            meterRegistry.counter("transact.ingestion.records.duplicates", "source", source).increment();
            log.debug("Duplicate transaction skipped: source={}, sourceTransactionId={}",
                source, transaction.getSourceTransactionId());
            return;
        }
        String message = e.getMessage() != null ? e.getMessage() : e.toString();
        log.error("Integrity failure persisting transaction from source {} ({}): {}",
            source, transaction.getSourceTransactionId(), message);
        transactionPersister.quarantineIntegrity(transaction, message);
    }

    /**
     * Only an explicit unique/duplicate signal counts as a duplicate —
     * never an indiscriminate catch of DataIntegrityViolationException.
     */
    private boolean isDuplicate(DataIntegrityViolationException e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof SQLException sqlEx && "23505".equals(sqlEx.getSQLState())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("duplicate") || lower.contains("idx_unique_source_transaction")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
