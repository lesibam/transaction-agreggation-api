package za.co.evilcorp.transact.config;

import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * One registered ingestion source, from app.sources.registry in application.yml.
 * Transport (type) is configurable; the payload-shape mapping (normalizer key)
 * resolves to a code-owned SourceNormalizer strategy — see ADR-011.
 */
public record SourceDescriptor(
        String id,
        String name,
        @DefaultValue("true") boolean enabled,
        Type type,
        String normalizer,
        Mock mock,
        Kafka kafka,
        S3 s3,
        Http http) {

    public enum Type { MOCK, KAFKA, S3, HTTP }

    /** type: MOCK — raw records from a classpath JSON file. */
    public record Mock(String dataLocation) {}

    /**
     * type: KAFKA — polls an external topic. Offsets are tracked in the
     * source_sync_state cursor ("partition:offset,partition:offset"), so the
     * pipeline stays at-least-once; autoOffsetReset only applies to the
     * very first fetch.
     */
    public record Kafka(
            String bootstrapServers,
            String topic,
            String groupId,
            @DefaultValue("earliest") String autoOffsetReset,
            @DefaultValue("2000") Integer pollTimeoutMs,
            @DefaultValue("500") Integer maxPollRecords) {}

    /**
     * type: S3 — lists objects under a prefix, lexicographically (startAfter =
     * cursor key). endpoint points at MinIO or another S3-compatible store.
     */
    public record S3(
            String endpoint,
            @DefaultValue("us-east-1") String region,
            String bucket,
            String prefix,
            String accessKey,
            String secretKey,
            @DefaultValue("10") Integer maxKeysPerFetch) {}

    /**
     * type: HTTP — GET baseUrl+path with the cursor passed as a query param.
     * Expected response contract: {"records": [...], "nextCursor": "..."}.
     */
    public record Http(
            String baseUrl,
            String path,
            @DefaultValue("cursor") String cursorParam,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {}
}
