package za.co.evilcorp.transact.api.dto;

import java.time.OffsetDateTime;

public record SourceHealthDto(
        String id,
        String name,
        String status,
        OffsetDateTime lastSuccessfulSync,
        OffsetDateTime lastAttempt,
        Integer failureCount,
        Long freshnessSeconds,
        String freshnessStatus) {
}
