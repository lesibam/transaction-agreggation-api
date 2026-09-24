package za.co.evilcorp.transact.infrastructure.integration.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.config.SourceDescriptor;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.infrastructure.integration.normalizer.SourceNormalizer;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * type: S3 transport adapter. Not a Spring bean: the source factory builds
 * one instance per configured SourceDescriptor. The S3Client is created once
 * and reused across fetches.
 *
 * Cursor protocol (at-least-once): the cursor is the last object key of the
 * fetched page and is passed back as startAfter. S3 lists keys
 * lexicographically, so one page per fetch continues where the previous
 * cycle stopped. Listing errors propagate to IngestionService (retry /
 * FAILED); a read failure of a single object only skips that object so a
 * listing-consistency edge case cannot kill the whole batch.
 */
@Slf4j
public class S3TransactionSource implements TransactionSource {

    private static final String DEFAULT_REGION = "us-east-1";
    private static final int DEFAULT_MAX_KEYS_PER_FETCH = 10;

    private final SourceDescriptor descriptor;
    private final SourceNormalizer normalizer;
    private final ObjectMapper objectMapper;
    private final S3Client s3Client;

    public S3TransactionSource(SourceDescriptor descriptor, SourceNormalizer normalizer, ObjectMapper objectMapper) {
        if (descriptor.s3() == null) {
            throw new IllegalStateException("Source " + descriptor.id() + " has no s3 configuration");
        }
        if (isBlank(descriptor.s3().bucket())) {
            throw new IllegalStateException("Source " + descriptor.id() + " has a blank s3 bucket");
        }
        this.descriptor = descriptor;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
        this.s3Client = buildClient(descriptor.s3());
    }

    private S3Client buildClient(SourceDescriptor.S3 s3) {
        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(isBlank(s3.region()) ? DEFAULT_REGION : s3.region()));
        if (!isBlank(s3.endpoint())) {
            // S3-compatible stores (e.g. MinIO) route by path; virtual-host
            // addressing against a plain host:port override misroutes bucket
            // operations (the SDK only falls back to path style for IP hosts).
            builder.endpointOverride(URI.create(s3.endpoint()));
            builder.forcePathStyle(true);
        }
        if (!isBlank(s3.accessKey()) && !isBlank(s3.secretKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())));
        }
        return builder.build();
    }

    @Override
    public String getSourceId() {
        return descriptor.id();
    }

    @Override
    public SourceResponse fetchTransactions(Optional<String> cursor) {
        SourceDescriptor.S3 s3 = descriptor.s3();
        int maxKeys = s3.maxKeysPerFetch() == null ? DEFAULT_MAX_KEYS_PER_FETCH : s3.maxKeysPerFetch();

        ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
            .bucket(s3.bucket())
            .maxKeys(maxKeys);
        if (!isBlank(s3.prefix())) {
            request.prefix(s3.prefix());
        }
        if (cursor.isPresent() && !cursor.get().isBlank()) {
            request.startAfter(cursor.get());
        }

        ListObjectsV2Response response = s3Client.listObjectsV2(request.build());
        List<S3Object> objects = response.contents() == null ? List.of() : response.contents();
        if (objects.isEmpty()) {
            log.info("S3 source {} fetched 0 key(s), 0 record(s), nextCursor=null", descriptor.id());
            return new SourceResponse(List.of(), null);
        }

        List<CanonicalTransaction> transactions = new ArrayList<>();
        int unreadableKeys = 0;
        for (S3Object object : objects) {
            try {
                byte[] bytes = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(s3.bucket())
                        .key(object.key())
                        .build())
                    .asByteArray();
                transactions.addAll(toCanonical(objectMapper.readTree(bytes), object.key()));
            } catch (S3Exception | IOException e) {
                unreadableKeys++;
                log.warn("S3 source {} skipping unreadable object {}: {}",
                    descriptor.id(), object.key(), e.getMessage());
            }
        }

        String nextCursor = objects.get(objects.size() - 1).key();
        log.info("S3 source {} fetched {} key(s), {} record(s), {} unreadable key(s), nextCursor={}",
            descriptor.id(), objects.size(), transactions.size(), unreadableKeys, nextCursor);

        return new SourceResponse(transactions, nextCursor);
    }

    private List<CanonicalTransaction> toCanonical(JsonNode node, String key) {
        if (node.isArray()) {
            List<CanonicalTransaction> entries = new ArrayList<>();
            for (JsonNode element : node) {
                entries.add(element.isObject() ? normalizer.normalize(element, descriptor.id()) : null);
            }
            return entries;
        }
        if (node.isObject()) {
            return List.of(normalizer.normalize(node, descriptor.id()));
        }
        throw new IllegalStateException("Source " + descriptor.id()
            + " expects object " + key + " to hold a JSON array or object, found " + node.getNodeType());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
