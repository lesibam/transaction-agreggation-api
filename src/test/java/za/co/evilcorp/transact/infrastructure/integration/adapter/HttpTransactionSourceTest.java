package za.co.evilcorp.transact.infrastructure.integration.adapter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import za.co.evilcorp.transact.application.port.TransactionSource.SourceResponse;
import za.co.evilcorp.transact.config.SourceDescriptor;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.infrastructure.integration.normalizer.SourceANormalizer;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpTransactionSourceTest {

    private static final String BASE_URL = "http://localhost:9000";
    private static final String PATH = "/transactions";

    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private MockRestServiceServer server;
    private HttpTransactionSource source;

    @BeforeEach
    void setUp() {
        source = new HttpTransactionSource(
            descriptor(), new SourceANormalizer(), objectMapper(), restClientBuilder);
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    @Test
    void fetchWithoutCursorReturnsNormalizedRecordsAndNextCursor() {
        server.expect(requestTo(BASE_URL + PATH))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {"records": [
                  {"id": "A100", "amount": -150, "currency": "ZAR", "description": "Grocery Store A", "merchantName": "Woolworths", "date": "2026-09-22T10:00:00Z"}
                ], "nextCursor": "page-2"}
                """, MediaType.APPLICATION_JSON));

        SourceResponse response = source.fetchTransactions(Optional.empty());

        server.verify();
        assertThat(response.transactions()).hasSize(1);
        CanonicalTransaction tx = response.transactions().get(0);
        assertThat(tx.getSourceId()).isEqualTo("source-a");
        assertThat(tx.getSourceTransactionId()).isEqualTo("A100");
        assertThat(tx.getAmount()).isEqualByComparingTo("150");
        assertThat(tx.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(tx.getCurrency()).isEqualTo("ZAR");
        assertThat(response.nextCursor()).isEqualTo("page-2");
    }

    @Test
    void fetchSendsCursorAsQueryParamAndAcceptsJson() {
        server.expect(requestTo(BASE_URL + PATH + "?cursor=page-1"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
            .andRespond(withSuccess("{\"records\": [], \"nextCursor\": \"page-2\"}", MediaType.APPLICATION_JSON));

        SourceResponse response = source.fetchTransactions(Optional.of("page-1"));

        server.verify();
        assertThat(response.transactions()).isEmpty();
        assertThat(response.nextCursor()).isEqualTo("page-2");
    }

    @Test
    void serverErrorPropagatesAsRestClientResponseException() {
        server.expect(requestTo(BASE_URL + PATH))
            .andRespond(withServerError());

        assertThatThrownBy(() -> source.fetchTransactions(Optional.empty()))
            .isInstanceOf(RestClientResponseException.class);
    }

    @Test
    void responseWithoutRecordsArrayFailsLoudlyNamingTheSource() {
        server.expect(requestTo(BASE_URL + PATH))
            .andRespond(withSuccess("{\"unexpected\": true}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> source.fetchTransactions(Optional.empty()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("source-a");
    }

    private static SourceDescriptor descriptor() {
        SourceDescriptor.Http http = new SourceDescriptor.Http(
            BASE_URL, PATH, "cursor", Duration.ofSeconds(5), Duration.ofSeconds(10));
        return new SourceDescriptor(
            "source-a", "Source A", true, SourceDescriptor.Type.HTTP, SourceANormalizer.KEY,
            null, null, null, http);
    }

    private static ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        return mapper;
    }
}
