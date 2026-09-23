package za.co.evilcorp.transact.api.dto;

import java.util.List;

public record SourceStatusResponse(List<SourceHealthDto> sources) {
}
