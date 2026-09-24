package za.co.evilcorp.transact.infrastructure.integration.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;

/**
 * Code-owned strategy that maps one raw source record (JSON) to the canonical
 * transaction model. Selected by the `normalizer` key in the source registry
 * (app.sources.registry[].normalizer) — the registry owns transport and
 * identity, this owns semantics. Never throws on bad data: missing/invalid
 * fields surface as nulls and are quarantined downstream by IngestionService.
 * See ADR-006 (normalization at the boundary) and ADR-011 (registry/config split).
 */
public interface SourceNormalizer {

    /** Registry key, e.g. "source-a-v1". Bump the version when the mapping changes. */
    String key();

    /** Maps one raw record to the canonical model for the given sourceId. */
    CanonicalTransaction normalize(JsonNode raw, String sourceId);
}
