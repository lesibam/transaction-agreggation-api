package za.co.evilcorp.transact.application.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import za.co.evilcorp.transact.application.port.TransactionEventPublisher;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.infrastructure.persistence.entity.SourceSyncStateEntity;
import za.co.evilcorp.transact.infrastructure.persistence.repository.IngestionErrorRepository;
import za.co.evilcorp.transact.infrastructure.persistence.repository.SourceSyncStateRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sync failure path: source fetch throws (after retries) →
 * source_sync_state.status=FAILED, failure_count incremented, last_error set,
 * last_successful_sync untouched (null for a never-successful source).
 * Pure unit test — no containers.
 */
class SyncFailureTest {

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
    }

    private IngestionService serviceWith(TransactionSource source) {
        return new IngestionService(
            List.of(source), syncStateRepository, eventPublisher, ingestionErrorRepository, meterRegistry,
            3, 1L);
    }

    private static TransactionSource throwingSource() {
        TransactionSource source = mock(TransactionSource.class);
        when(source.getSourceId()).thenReturn("SOURCE_BROKEN");
        when(source.fetchTransactions(any())).thenThrow(new RuntimeException("connect timeout"));
        return source;
    }

    @Test
    void fetchFailureMarksSyncStateFailedWithLastErrorAndNoSuccessfulSync() {
        IngestionService service = serviceWith(throwingSource());

        service.ingestAllSources();

        ArgumentCaptor<SourceSyncStateEntity> captor =
            ArgumentCaptor.forClass(SourceSyncStateEntity.class);
        verify(syncStateRepository, times(2)).save(captor.capture());
        SourceSyncStateEntity finalState = captor.getAllValues()
            .get(captor.getAllValues().size() - 1);

        assertThat(finalState.getSourceId()).isEqualTo("SOURCE_BROKEN");
        assertThat(finalState.getStatus()).isEqualTo("FAILED");
        assertThat(finalState.getFailureCount()).isEqualTo(1);
        assertThat(finalState.getLastError()).isNotBlank();
        assertThat(finalState.getLastError()).contains("connect timeout");
        assertThat(finalState.getLastSuccessfulSync()).isNull();
        assertThat(finalState.getLastAttemptedSync()).isNotNull();

        verify(eventPublisher, never()).publish(any());
        verify(ingestionErrorRepository, never()).save(any());
    }

    @Test
    void repeatedFailuresIncrementFailureCount() {
        TransactionSource source = throwingSource();
        IngestionService service = serviceWith(source);
        SourceSyncStateEntity persisted = SourceSyncStateEntity.builder()
            .sourceId("SOURCE_BROKEN")
            .status("FAILED")
            .failureCount(2)
            .lastError("previous error")
            .lastAttemptedSync(OffsetDateTime.now().minusMinutes(10))
            .createdAt(OffsetDateTime.now().minusHours(1))
            .createdBy("SYSTEM_INGESTION")
            .updatedAt(OffsetDateTime.now().minusHours(1))
            .updatedBy("SYSTEM_INGESTION")
            .build();
        when(syncStateRepository.findById("SOURCE_BROKEN")).thenReturn(Optional.of(persisted));
        when(syncStateRepository.save(any(SourceSyncStateEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service.ingestAllSources();

        ArgumentCaptor<SourceSyncStateEntity> captor =
            ArgumentCaptor.forClass(SourceSyncStateEntity.class);
        verify(syncStateRepository).save(captor.capture());
        SourceSyncStateEntity state = captor.getValue();
        assertThat(state.getStatus()).isEqualTo("FAILED");
        assertThat(state.getFailureCount()).isEqualTo(3);
        assertThat(state.getLastError()).contains("connect timeout");
        assertThat(state.getLastSuccessfulSync()).isNull();

        double failureCount = meterRegistry.find("transact.ingestion.source.sync.failure")
            .tag("source", "SOURCE_BROKEN").counters().stream().mapToDouble(c -> c.count()).sum();
        assertThat(failureCount).isEqualTo(1.0);
    }

    @Test
    void successAfterFailureResetsStateToSuccess() {
        SourceSyncStateEntity persisted = SourceSyncStateEntity.builder()
            .sourceId("SOURCE_FLAKY")
            .status("FAILED")
            .failureCount(4)
            .lastError("boom")
            .createdAt(OffsetDateTime.now().minusHours(1))
            .createdBy("SYSTEM_INGESTION")
            .updatedAt(OffsetDateTime.now().minusHours(1))
            .updatedBy("SYSTEM_INGESTION")
            .build();
        when(syncStateRepository.findById("SOURCE_FLAKY")).thenReturn(Optional.of(persisted));

        za.co.evilcorp.transact.domain.model.CanonicalTransaction record =
            za.co.evilcorp.transact.domain.model.CanonicalTransaction.builder()
                .sourceId("SOURCE_FLAKY")
                .sourceTransactionId("R1")
                .amount(java.math.BigDecimal.ONE)
                .currency("ZAR")
                .direction(za.co.evilcorp.transact.domain.model.TransactionDirection.DEBIT)
                .transactionDate(OffsetDateTime.now())
                .normalizationVersion("1")
                .ingestedAt(OffsetDateTime.now())
                .build();
        TransactionSource healthy = mock(TransactionSource.class);
        when(healthy.getSourceId()).thenReturn("SOURCE_FLAKY");
        when(healthy.fetchTransactions(any()))
            .thenReturn(new TransactionSource.SourceResponse(List.of(record), "cursor-1"));

        IngestionService service = serviceWith(healthy);
        service.ingestAllSources();

        ArgumentCaptor<SourceSyncStateEntity> captor =
            ArgumentCaptor.forClass(SourceSyncStateEntity.class);
        verify(syncStateRepository).save(captor.capture());
        SourceSyncStateEntity state = captor.getValue();
        assertThat(state.getStatus()).isEqualTo("SUCCESS");
        assertThat(state.getFailureCount()).isZero();
        assertThat(state.getLastSuccessfulSync()).isNotNull();
        assertThat(state.getLastError()).contains("boom");
    }
}
