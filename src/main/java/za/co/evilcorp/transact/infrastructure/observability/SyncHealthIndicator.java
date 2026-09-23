package za.co.evilcorp.transact.infrastructure.observability;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import za.co.evilcorp.transact.infrastructure.persistence.repository.SourceSyncStateRepository;

import java.util.Map;
import java.util.stream.Collectors;

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

        boolean allSuccessful = sourceStatuses.values().stream().allMatch("SUCCESS"::equals);
        
        if (allSuccessful) {
            return Health.up()
                .withDetail("sources", sourceStatuses)
                .withDetail("status", "All sources synced successfully")
                .build();
        }

        return Health.down()
            .withDetail("sources", sourceStatuses)
            .withDetail("status", "One or more sources are failing sync")
            .build();
    }
}
