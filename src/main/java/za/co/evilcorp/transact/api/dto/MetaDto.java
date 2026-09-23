package za.co.evilcorp.transact.api.dto;

public record MetaDto(String nextCursor, boolean hasMore, String completeness, FreshnessDto freshness) {
}
