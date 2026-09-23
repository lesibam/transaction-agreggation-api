package za.co.evilcorp.transact.application.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import za.co.evilcorp.transact.application.port.TransactionEventPublisher;
import za.co.evilcorp.transact.application.port.TransactionIngestedEvent;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.infrastructure.persistence.entity.IngestionErrorEntity;
import za.co.evilcorp.transact.infrastructure.persistence.entity.SourceSyncStateEntity;
import za.co.evilcorp.transact.infrastructure.persistence.repository.IngestionErrorRepository;
import za.co.evilcorp.transact.infrastructure.persistence.repository.SourceSyncStateRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Quarantine: a record with a missing mandatory field (null amount) is written
 * to ingestion_error as VALIDATION, while valid records in the same batch are
 * still published. Pure unit test — no containers.
 */
class QuarantineTest {

    private SourceSyncStateRepository syncStateRepository;
    private TransactionEventPublisher eventPublisher;
    private IngestionErrorRepository ingestionErrorRepository;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        syncStateRepository = mock(SourceSyncStateRepository.class);
        eventPublisher = mock(TransactionEventPublisher.class);
        ingestionErrorRepository = mock(IngestionErrorRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        when(syncStateRepository.save(any(SourceSyncStateEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(syncStateRepository.findById(any())).thenReturn(Optional.empty());
        when(ingestionErrorRepository.save(any(IngestionErrorEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void nullAmountRecordIsQuarantinedAndValidRecordStillPublished() {
        CanonicalTransaction valid = CanonicalTransaction.builder()
            .sourceId("SOURCE_X")
            .sourceTransactionId("VALID-1")
            .amount(BigDecimal.TEN)
            .currency("ZAR")
            .direction(TransactionDirection.DEBIT)
            .transactionDate(OffsetDateTime.now())
            .normalizationVersion("1")
            .ingestedAt(OffsetDateTime.now())
            .build();
        CanonicalTransaction malformed = CanonicalTransaction.builder()
            .sourceId("SOURCE_X")
            .sourceTransactionId("BROKEN-1")
            .amount(null)
            .currency("ZAR")
            .direction(TransactionDirection.DEBIT)
            .transactionDate(OffsetDateTime.now())
            .normalizationVersion("1")
            .ingestedAt(OffsetDateTime.now())
            .build();

        TransactionSource source = mock(TransactionSource.class);
        when(source.getSourceId()).thenReturn("SOURCE_X");
        when(source.fetchTransactions(any()))
            .thenReturn(new TransactionSource.SourceResponse(List.of(valid, malformed), "cursor-1"));

        IngestionService service = new IngestionService(
            List.of(source), syncStateRepository, eventPublisher, ingestionErrorRepository, meterRegistry,
            1, 0L);

        service.ingestAllSources();

        verify(ingestionErrorRepository, times(1)).save(any(IngestionErrorEntity.class));
        ArgumentCaptor<IngestionErrorEntity> errorCaptor =
            ArgumentCaptor.forClass(IngestionErrorEntity.class);
        verify(ingestionErrorRepository).save(errorCaptor.capture());
        IngestionErrorEntity error = errorCaptor.getValue();
        assertThat(error.getErrorType()).isEqualTo("VALIDATION");
        assertThat(error.getSourceId()).isEqualTo("SOURCE_X");
        assertThat(error.getSourceTransactionId()).isEqualTo("BROKEN-1");
        assertThat(error.getErrorMessage()).contains("amount");
        assertThat(error.isRetryable()).isFalse();

        verify(eventPublisher, times(1)).publish(any(TransactionIngestedEvent.class));
        ArgumentCaptor<TransactionIngestedEvent> publishCaptor =
            ArgumentCaptor.forClass(TransactionIngestedEvent.class);
        verify(eventPublisher).publish(publishCaptor.capture());
        assertThat(publishCaptor.getValue().transaction().getSourceTransactionId()).isEqualTo("VALID-1");

        double quarantined = meterRegistry.find("transact.ingestion.records.quarantined")
            .tag("source", "SOURCE_X").counters().stream().mapToDouble(c -> c.count()).sum();
        double published = meterRegistry.find("transact.ingestion.records.published")
            .tag("source", "SOURCE_X").counters().stream().mapToDouble(c -> c.count()).sum();
        assertThat(quarantined).isEqualTo(1.0);
        assertThat(published).isEqualTo(1.0);

        ArgumentCaptor<SourceSyncStateEntity> stateCaptor =
            ArgumentCaptor.forClass(SourceSyncStateEntity.class);
        verify(syncStateRepository, times(2)).save(stateCaptor.capture());
        SourceSyncStateEntity finalState = stateCaptor.getAllValues()
            .get(stateCaptor.getAllValues().size() - 1);
        assertThat(finalState.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void multipleMalformedRecordsAreEachQuarantinedWithoutAbortingBatch() {
        CanonicalTransaction valid = CanonicalTransaction.builder()
            .sourceId("SOURCE_X")
            .sourceTransactionId("VALID-9")
            .amount(BigDecimal.ONE)
            .currency("ZAR")
            .direction(TransactionDirection.CREDIT)
            .transactionDate(OffsetDateTime.now())
            .normalizationVersion("1")
            .ingestedAt(OffsetDateTime.now())
            .build();
        CanonicalTransaction missingAmount = CanonicalTransaction.builder()
            .sourceId("SOURCE_X")
            .sourceTransactionId("BAD-AMOUNT")
            .amount(null)
            .currency("ZAR")
            .direction(TransactionDirection.DEBIT)
            .transactionDate(OffsetDateTime.now())
            .build();
        CanonicalTransaction missingCurrency = CanonicalTransaction.builder()
            .sourceId("SOURCE_X")
            .sourceTransactionId("BAD-CURRENCY")
            .amount(BigDecimal.ONE)
            .currency(null)
            .direction(TransactionDirection.DEBIT)
            .transactionDate(OffsetDateTime.now())
            .build();

        TransactionSource source = mock(TransactionSource.class);
        when(source.getSourceId()).thenReturn("SOURCE_X");
        when(source.fetchTransactions(any())).thenReturn(new TransactionSource.SourceResponse(
            List.of(missingAmount, valid, missingCurrency), "cursor-1"));

        IngestionService service = new IngestionService(
            List.of(source), syncStateRepository, eventPublisher, ingestionErrorRepository, meterRegistry,
            1, 0L);

        service.ingestAllSources();

        verify(ingestionErrorRepository, times(2)).save(any(IngestionErrorEntity.class));
        verify(eventPublisher, times(1)).publish(any(TransactionIngestedEvent.class));
    }
}
