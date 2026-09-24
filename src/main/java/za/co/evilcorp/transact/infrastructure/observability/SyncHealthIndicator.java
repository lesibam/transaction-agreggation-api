package za.co.evilcorp.transact.infrastructure.observability;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import za.co.evilcorp.transact.infrastructure.persistence.repository.SourceSyncStateRepository;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Contributes per-source sync state to /actuator/health as DETAIL, without
 * failing the overall health verdict.
 *
 * The health endpoint answers "is this instance ready to serve traffic?" —
 * and this system is explicitly designed to serve (partial) results while a
 * source is failing or starting up: INITIAL is a normal startup transient,
 * not a failure, and a FAILED source must surface through freshness metadata,
 * /v1/admin/sources, metrics and logs rather than 503-ing the API and
 * pulling the instance from rotation. Database reachability still gates the
 * overall health endpoint via Boot's own contributor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncHealthIndicator implements HealthIndicator {

    private final SourceSyncStateRepository syncStateRepository;

    @Override
    public Health health() {
        Map<String, String> sourceStatuses = syncStateRepository.findAll().stream()
            .collect(Collectors.toMap(
                s -> s.getSourceId(),
                s -> s.getStatus()
            ));

        return Health.up()
            .withDetail("sources", sourceStatuses)
            .withDetail("allSuccessful", sourceStatuses.values().stream().allMatch("SUCCESS"::equals))
            .build();
    }
}
