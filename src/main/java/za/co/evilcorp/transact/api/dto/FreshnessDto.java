package za.co.evilcorp.transact.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record FreshnessDto(String status, OffsetDateTime generatedAt, List<SourceFreshness> sources) {

    public record SourceFreshness(String source, OffsetDateTime lastSync, String status) {
    }
}
