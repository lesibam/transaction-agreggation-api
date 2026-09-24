package za.co.evilcorp.transact.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * The single source registry (app.sources.registry): drives which sources the
 * ingestion pipeline runs AND which sources the API's freshness/completeness
 * metadata reports. Registered-but-disabled sources intentionally appear in
 * freshness as UNKNOWN / completeness as PARTIAL — a disabled source never
 * lies about data it is not syncing. See ADR-011.
 */
@ConfigurationProperties("app.sources")
public record SourceRegistryProperties(List<SourceDescriptor> registry) {

    public List<SourceDescriptor> all() {
        return registry == null ? List.of() : registry;
    }

    public List<String> allIds() {
        return all().stream().map(SourceDescriptor::id).distinct().toList();
    }
}
