package za.co.evilcorp.transact.infrastructure.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import za.co.evilcorp.transact.infrastructure.persistence.entity.SourceSyncStateEntity;
import za.co.evilcorp.transact.infrastructure.persistence.repository.SourceSyncStateRepository;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The health verdict must never flap on ingestion state: INITIAL is a startup
 * transient and FAILED is surfaced through freshness metadata/admin/metrics —
 * the API serves partial results by design. CI proved the old behavior was a
 * real race: Docker's probe landed in an UP window and the smoke request 18ms
 * later hit a 503 because the first cycle had just written INITIAL rows.
 */
class SyncHealthIndicatorTest {

    private final SourceSyncStateRepository repository = mock(SourceSyncStateRepository.class);
    private final SyncHealthIndicator indicator = new SyncHealthIndicator(repository);

    private static SourceSyncStateEntity state(String id, String status) {
        SourceSyncStateEntity entity = new SourceSyncStateEntity();
        entity.setSourceId(id);
        entity.setStatus(status);
        return entity;
    }

    @Test
    void noSourcesYetIsUp() {
        when(repository.findAll()).thenReturn(List.of());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void initialRowsDuringFirstSyncAreUp() {
        when(repository.findAll()).thenReturn(List.of(
            state("SOURCE_A", "INITIAL"),
            state("SOURCE_B", "INITIAL")));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsAllEntriesOf(Map.of(
            "sources", Map.of("SOURCE_A", "INITIAL", "SOURCE_B", "INITIAL"),
            "allSuccessful", false));
    }

    @Test
    void failedSourceIsUpWithDetailNotDown() {
        when(repository.findAll()).thenReturn(List.of(
            state("SOURCE_A", "SUCCESS"),
            state("SOURCE_B", "FAILED")));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsAllEntriesOf(Map.of(
            "sources", Map.of("SOURCE_A", "SUCCESS", "SOURCE_B", "FAILED"),
            "allSuccessful", false));
    }

    @Test
    void allSuccessfulIsUpWithDetail() {
        when(repository.findAll()).thenReturn(List.of(
            state("SOURCE_A", "SUCCESS"),
            state("SOURCE_B", "SUCCESS")));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsAllEntriesOf(Map.of(
            "sources", Map.of("SOURCE_A", "SUCCESS", "SOURCE_B", "SUCCESS"),
            "allSuccessful", true));
    }
}
