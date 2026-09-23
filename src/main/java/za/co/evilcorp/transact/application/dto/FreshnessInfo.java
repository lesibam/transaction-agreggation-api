package za.co.evilcorp.transact.application.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record FreshnessInfo(String status, OffsetDateTime generatedAt, List<SourceFreshnessInfo> sources) {
}
