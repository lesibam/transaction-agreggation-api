package za.co.evilcorp.transact.infrastructure.integration.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.config.SourceDescriptor;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.infrastructure.integration.normalizer.SourceNormalizer;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * HTTP transport for {@link TransactionSource}: GET {baseUrl}{path}, passing
 * the cursor as the {@code cursorParam} query parameter when present, with
 * the response contract {"records": [...], "nextCursor": "..."} normalized
 * via the source's {@link SourceNormalizer}. Not a Spring bean — a factory
 * creates one instance per registered descriptor.
 *
 * <p>HTTP error responses are deliberately not caught: the
 * {@code RestClientResponseException} propagates so IngestionService's retry
 * wrapper owns retries and source failure marking. A null nextCursor means
 * the source is exhausted. Individual records that are not JSON objects
 * surface as null list entries — IngestionService quarantines them
 * downstream.
 *
 * <p>The constructor installs a {@link SimpleClientHttpRequestFactory} with
 * the descriptor's connect/read timeouts on the builder and the
 * {@link RestClient} is built from it on each fetch: {@code RestClient}
 * snapshots the request factory at build time, so building eagerly would
 * freeze out MockRestServiceServer (tests bind it to the same builder) while
 * installing the factory after binding would replace the mock.
 */
@Slf4j
public class HttpTransactionSource implements TransactionSource {

    private final SourceDescriptor descriptor;
    private final SourceNormalizer normalizer;
    private final ObjectMapper objectMapper;
    private final SourceDescriptor.Http http;
    private final RestClient.Builder restClientBuilder;

    public HttpTransactionSource(SourceDescriptor descriptor,
                                 SourceNormalizer normalizer,
                                 ObjectMapper objectMapper,
                                 RestClient.Builder restClientBuilder) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.restClientBuilder = Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null");
        this.http = requireHttp();
        this.restClientBuilder.requestFactory(requestFactory());
    }

    @Override
    public String getSourceId() {
        return descriptor.id();
    }

    @Override
    public SourceResponse fetchTransactions(Optional<String> cursor) {
        String current = cursor.isEmpty() ? null : cursor.orElse(null);
        log.info("Fetching transactions from HTTP source '{}' at '{}' with cursor: {}",
            getSourceId(), http.baseUrl() + http.path(), current);
        URI uri = buildUri(current);
        String body = restClientBuilder.build()
            .get()
            .uri(uri)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(String.class);
        Page page = parsePage(body);
        List<CanonicalTransaction> transactions = normalize(page.records());
        return new SourceResponse(transactions, page.nextCursor());
    }

    private SourceDescriptor.Http requireHttp() {
        SourceDescriptor.Http http = descriptor.http();
        if (http == null || isBlank(http.baseUrl()) || isBlank(http.path())) {
            throw new IllegalStateException(
                "Source '" + descriptor.id() + "': configured as HTTP but baseUrl/path is missing");
        }
        return http;
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) http.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) http.readTimeout().toMillis());
        return requestFactory;
    }

    private URI buildUri(String cursor) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(http.baseUrl()).path(http.path());
        if (cursor != null && !cursor.isBlank()) {
            builder.queryParam(http.cursorParam(), cursor);
        }
        return builder.encode().build().toUri();
    }

    private Page parsePage(String body) {
        JsonNode root = readBody(body);
        JsonNode recordsNode = root.get("records");
        if (recordsNode == null || !recordsNode.isArray()) {
            throw new IllegalStateException(
                "Source '" + getSourceId() + "': HTTP response has no \"records\" array (contract violation)");
        }
        List<JsonNode> rawRecords = new ArrayList<>(recordsNode.size());
        for (JsonNode rawRecord : recordsNode) {
            rawRecords.add(rawRecord);
        }
        JsonNode nextCursorNode = root.get("nextCursor");
        String nextCursor = nextCursorNode == null || nextCursorNode.isNull() ? null : nextCursorNode.asText();
        return new Page(rawRecords, nextCursor);
    }

    private JsonNode readBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException(
                "Source '" + getSourceId() + "': HTTP response body is empty (contract violation)");
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Source '" + getSourceId() + "': HTTP response body is not valid JSON", e);
        }
    }

    private List<CanonicalTransaction> normalize(List<JsonNode> rawRecords) {
        List<CanonicalTransaction> transactions = new ArrayList<>(rawRecords.size());
        for (JsonNode rawRecord : rawRecords) {
            transactions.add(rawRecord.isObject() ? normalizer.normalize(rawRecord, getSourceId()) : null);
        }
        return transactions;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Page(List<JsonNode> records, String nextCursor) {}
}
