package za.co.evilcorp.transact.infrastructure.integration.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.config.SourceDescriptor;
import za.co.evilcorp.transact.config.SourceRegistryProperties;
import za.co.evilcorp.transact.infrastructure.integration.normalizer.SourceNormalizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the ingestion pipeline's source list from the config registry
 * (app.sources.registry — see ADR-011). Validation is fail-fast at startup:
 * unknown normalizer keys, duplicate ids and missing transport config must
 * never surface as a runtime surprise mid-cycle.
 */
@Slf4j
@Configuration
public class SourceAdapterConfiguration {

    @Bean
    public List<TransactionSource> transactionSources(
            SourceRegistryProperties registry,
            List<SourceNormalizer> normalizerBeans,
            ObjectMapper objectMapper) {

        Map<String, SourceNormalizer> normalizers = new LinkedHashMap<>();
        for (SourceNormalizer normalizer : normalizerBeans) {
            SourceNormalizer existing = normalizers.put(normalizer.key(), normalizer);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate source normalizer key: " + normalizer.key());
            }
        }

        Set<String> seenIds = new HashSet<>();
        List<TransactionSource> sources = new ArrayList<>();
        for (SourceDescriptor descriptor : registry.all()) {
            validate(descriptor, seenIds, normalizers);
            if (!descriptor.enabled()) {
                log.info("Source '{}' is registered but disabled — freshness metadata will " +
                        "report it as UNKNOWN and completeness as PARTIAL", descriptor.id());
                continue;
            }
            sources.add(build(descriptor, normalizers.get(descriptor.normalizer()), objectMapper));
        }
        if (sources.isEmpty()) {
            log.warn("No enabled ingestion sources configured — ingestion cycles are no-ops " +
                    "and all sources report UNKNOWN/PARTIAL freshness");
        }
        return sources;
    }

    private void validate(SourceDescriptor descriptor, Set<String> seenIds,
                           Map<String, SourceNormalizer> normalizers) {
        if (descriptor.id() == null || descriptor.id().isBlank()) {
            throw new IllegalStateException("Source registry entry is missing an id");
        }
        if (!seenIds.add(descriptor.id())) {
            throw new IllegalStateException("Duplicate source id in registry: " + descriptor.id());
        }
        if (descriptor.type() == null) {
            throw new IllegalStateException(
                    "Source '" + descriptor.id() + "' is missing a type (MOCK/KAFKA/S3/HTTP)");
        }
        if (descriptor.normalizer() == null || !normalizers.containsKey(descriptor.normalizer())) {
            throw new IllegalStateException("Source '" + descriptor.id()
                    + "' references unknown normalizer key '" + descriptor.normalizer()
                    + "' — known keys: " + normalizers.keySet());
        }
    }

    private TransactionSource build(SourceDescriptor descriptor, SourceNormalizer normalizer,
                                    ObjectMapper objectMapper) {
        return switch (descriptor.type()) {
            case MOCK -> new MockTransactionSource(descriptor, normalizer, objectMapper);
            case KAFKA -> new KafkaTransactionSource(descriptor, normalizer, objectMapper);
            case S3 -> new S3TransactionSource(descriptor, normalizer, objectMapper);
            case HTTP -> new HttpTransactionSource(descriptor, normalizer, objectMapper,
                    RestClient.builder());
        };
    }
}
