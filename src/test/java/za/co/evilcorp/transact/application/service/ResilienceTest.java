package za.co.evilcorp.transact.application.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import za.co.evilcorp.transact.application.port.TransactionEventPublisher;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.infrastructure.persistence.entity.SourceSyncStateEntity;
import za.co.evilcorp.transact.infrastructure.persistence.repository.IngestionErrorRepository;
import za.co.evilcorp.transact.infrastructure.persistence.repository.SourceSyncStateRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Failure isolation: one source blowing up must not stop the others, and each
 * source's sync state must independently end up FAILED / SUCCESS.
 * Pure unit test — no containers, no Spring context.
 */
class ResilienceTest {

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

    private IngestionService service(List<TransactionSource> sources) {
        return new IngestionService(
            sources, syncStateRepository, eventPublisher, ingestionErrorRepository, meterRegistry,
            1, 0L);
    }

    private static TransactionSource failingSource(String sourceId) {
        TransactionSource source = mock(TransactionSource.class);
        when(source.getSourceId()).thenReturn(sourceId);
        when(source.fetchTransactions(any())).thenThrow(new RuntimeException("API Down"));
        return source;
    }

    private static TransactionSource healthySource(String sourceId, String sourceTransactionId) {
        TransactionSource source = mock(TransactionSource.class);
        when(source.getSourceId()).thenReturn(sourceId);
        when(source.fetchTransactions(any())).thenReturn(new TransactionSource.SourceResponse(
            List.of(validRecord(sourceId, sourceTransactionId)), "cursor-1"));
        return source;
    }

    private static CanonicalTransaction validRecord(String sourceId, String sourceTransactionId) {
        return CanonicalTransaction.builder()
            .sourceId(sourceId)
            .sourceTransactionId(sourceTransactionId)
            .amount(BigDecimal.TEN)
            .currency("ZAR")
            .direction(TransactionDirection.DEBIT)
            .transactionDate(OffsetDateTime.now())
            .normalizationVersion("1")
            .ingestedAt(OffsetDateTime.now())
            .build();
    }

    @Test
    void failingSourceDoesNotPreventHealthySourceFromPublishing() {
        TransactionSource failing = failingSource("SOURCE_FAIL");
        TransactionSource healthy = healthySource("SOURCE_OK", "OK-1");

        IngestionService service = service(List.of(failing, healthy));

        service.ingestAllSources();

        verify(eventPublisher, times(1)).publish(any());
        ArgumentCaptor<za.co.evilcorp.transact.application.port.TransactionIngestedEvent> captor =
            ArgumentCaptor.forClass(za.co.evilcorp.transact.application.port.TransactionIngestedEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().source()).isEqualTo("SOURCE_OK");
        assertThat(captor.getValue().transaction().getSourceTransactionId()).isEqualTo("OK-1");
    }

    @Test
    void failingSourceMarkedFailedAndHealthySourceMarkedSuccess() {
        TransactionSource failing = failingSource("SOURCE_FAIL");
        TransactionSource healthy = healthySource("SOURCE_OK", "OK-1");

        IngestionService service = service(List.of(failing, healthy));

        service.ingestAllSources();

        ArgumentCaptor<SourceSyncStateEntity> captor =
            ArgumentCaptor.forClass(SourceSyncStateEntity.class);
        verify(syncStateRepository, times(4)).save(captor.capture());

        SourceSyncStateEntity failed = finalStateFor(captor, "SOURCE_FAIL");
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getFailureCount()).isGreaterThanOrEqualTo(1);
        assertThat(failed.getLastError()).isNotBlank();
        assertThat(failed.getLastSuccessfulSync()).isNull();

        SourceSyncStateEntity succeeded = finalStateFor(captor, "SOURCE_OK");
        assertThat(succeeded.getStatus()).isEqualTo("SUCCESS");
        assertThat(succeeded.getFailureCount()).isZero();
        assertThat(succeeded.getLastSuccessfulSync()).isNotNull();
        assertThat(succeeded.getCursor()).isEqualTo("cursor-1");
    }

    @Test
    void failureCounterAndSuccessCounterRecordedPerSource() {
        IngestionService service = service(List.of(
            failingSource("SOURCE_FAIL"), healthySource("SOURCE_OK", "OK-1")));

        service.ingestAllSources();

        double failure = meterRegistry.find("transact.ingestion.source.sync.failure")
            .tag("source", "SOURCE_FAIL").counters().stream()
            .mapToDouble(c -> c.count()).sum();
        double success = meterRegistry.find("transact.ingestion.source.sync.success")
            .tag("source", "SOURCE_OK").counters().stream()
            .mapToDouble(c -> c.count()).sum();
        double duration = meterRegistry.find("transact.ingestion.duration")
            .tag("source", "SOURCE_FAIL").timers().size();

        assertThat(failure).isEqualTo(1.0);
        assertThat(success).isEqualTo(1.0);
        assertThat(duration).isEqualTo(1);
        verify(ingestionErrorRepository, never()).save(any());
    }

    private static SourceSyncStateEntity finalStateFor(
            ArgumentCaptor<SourceSyncStateEntity> captor, String sourceId) {
        return captor.getAllValues().stream()
            .filter(s -> sourceId.equals(s.getSourceId()))
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("No sync state saved for " + sourceId));
    }
}
