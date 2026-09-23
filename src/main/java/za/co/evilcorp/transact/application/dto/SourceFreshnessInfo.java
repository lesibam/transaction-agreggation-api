package za.co.evilcorp.transact.application.dto;

import java.time.OffsetDateTime;

public record SourceFreshnessInfo(String source, OffsetDateTime lastSync, String status) {
}
