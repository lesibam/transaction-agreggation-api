package za.co.evilcorp.transact.infrastructure.integration.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.config.SourceDescriptor;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.infrastructure.integration.normalizer.SourceNormalizer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * MOCK transport for {@link TransactionSource}: reads raw records from the
 * classpath file named by {@code descriptor.mock().dataLocation()}
 * ("classpath:mock/source-x.json") and normalizes each one via the source's
 * {@link SourceNormalizer}. Not a Spring bean — a factory creates one
 * instance per registered descriptor.
 *
 * <p>The first fetch (null/blank cursor) returns every record together with
 * {@link #EXHAUSTED_CURSOR}; any subsequent fetch with that cursor returns
 * nothing, which keeps mock re-runs idempotent. Individual records that are
 * not JSON objects surface as null list entries — IngestionService
 * quarantines them downstream; the adapter never throws for bad records.
 */
@Slf4j
public class MockTransactionSource implements TransactionSource {

    static final String EXHAUSTED_CURSOR = "MOCK_EXHAUSTED";
    private static final String CLASSPATH_PREFIX = "classpath:";

    private final SourceDescriptor descriptor;
    private final SourceNormalizer normalizer;
    private final ObjectMapper objectMapper;

    public MockTransactionSource(SourceDescriptor descriptor,
                                 SourceNormalizer normalizer,
                                 ObjectMapper objectMapper) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public String getSourceId() {
        return descriptor.id();
    }

    @Override
    public SourceResponse fetchTransactions(Optional<String> cursor) {
        String current = cursor == null ? null : cursor.orElse(null);
        if (current != null && !current.isBlank()) {
            log.info("Fetching transactions from mock source '{}': already consumed (cursor '{}')",
                getSourceId(), current);
            return new SourceResponse(List.of(), current);
        }
        log.info("Fetching transactions from mock source '{}': reading all records (no cursor)", getSourceId());
        List<JsonNode> rawRecords = readRawRecords();
        List<CanonicalTransaction> transactions = normalize(rawRecords);
        return new SourceResponse(transactions, EXHAUSTED_CURSOR);
    }

    private List<JsonNode> readRawRecords() {
        String location = requireDataLocation();
        JsonNode root = readRoot(location);
        if (root == null || !root.isArray()) {
            throw new IllegalStateException(
                "Source '" + getSourceId() + "': mock data at '" + location + "' is not a JSON array");
        }
        List<JsonNode> rawRecords = new ArrayList<>(root.size());
        for (JsonNode rawRecord : root) {
            rawRecords.add(rawRecord);
        }
        return rawRecords;
    }

    private String requireDataLocation() {
        SourceDescriptor.Mock mock = descriptor.mock();
        String location = mock == null ? null : mock.dataLocation();
        if (location == null || location.isBlank()) {
            throw new IllegalStateException(
                "Source '" + getSourceId() + "': configured as MOCK but no mock dataLocation is set");
        }
        return location;
    }

    private JsonNode readRoot(String location) {
        String path = location.startsWith(CLASSPATH_PREFIX)
            ? location.substring(CLASSPATH_PREFIX.length())
            : location;
        try (InputStream inputStream = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readTree(inputStream);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Source '" + getSourceId() + "': cannot read mock data at '" + location + "'", e);
        }
    }

    private List<CanonicalTransaction> normalize(List<JsonNode> rawRecords) {
        List<CanonicalTransaction> transactions = new ArrayList<>(rawRecords.size());
        for (JsonNode rawRecord : rawRecords) {
            transactions.add(rawRecord.isObject() ? normalizer.normalize(rawRecord, getSourceId()) : null);
        }
        return transactions;
    }
}
