package za.co.evilcorp.transact.application.dto;

import java.time.OffsetDateTime;

public record SourceStatusResult(
        String id,
        String name,
        String status,
        OffsetDateTime lastSuccessfulSync,
        OffsetDateTime lastAttempt,
        Integer failureCount,
        Long freshnessSeconds,
        String freshnessStatus) {
}
