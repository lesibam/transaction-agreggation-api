package za.co.evilcorp.transact.application.dto;

public record MetaInfo(String nextCursor, boolean hasMore, String completeness, FreshnessInfo freshness) {
}
