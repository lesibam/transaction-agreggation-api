package za.co.evilcorp.transact.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import za.co.evilcorp.transact.support.AbstractIntegrationTest;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant isolation on GET /v1/customers/{id}/transactions:
 * owner → 200, other customer → 403 ProblemDetail, no token → 401.
 */
class ApiIsolationTest extends AbstractIntegrationTest {

    @Test
    void ownerCanListOwnTransactions() {
        String token = mintToken(DEMO_CUSTOMER_ID, "CUSTOMER");

        HttpResponse<String> response = get(
            "/v1/customers/" + DEMO_CUSTOMER_ID + "/transactions?limit=5", token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .startsWith("application/json");
        JsonNode body = jsonBody(response);
        assertThat(body.has("data")).isTrue();
        assertThat(body.get("data").isArray()).isTrue();
        assertThat(body.has("meta")).isTrue();
        assertThat(body.get("meta").get("completeness").asText())
            .isIn("COMPLETE", "PARTIAL");
        assertThat(body.get("meta").get("freshness").get("status").asText())
            .isIn("FRESH", "STALE", "VERY_STALE", "UNKNOWN");
    }

    @Test
    void requestingAnotherCustomerIsForbiddenWithProblemDetail() {
        String token = mintToken(DEMO_CUSTOMER_ID, "CUSTOMER");
        UUID otherCustomerId = UUID.randomUUID();

        HttpResponse<String> response = get(
            "/v1/customers/" + otherCustomerId + "/transactions", token);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .startsWith("application/problem+json");

        JsonNode body = jsonBody(response);
        assertThat(body.get("title").asText()).isEqualTo("Forbidden");
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("type").asText()).contains("forbidden");
        assertThat(body.get("detail").asText()).contains("Access denied");
        assertThat(body.get("instance").asText())
            .isEqualTo("/v1/customers/" + otherCustomerId + "/transactions");
        assertThat(body.has("traceId")).isTrue();
        assertThat(body.get("traceId").isNull()).isFalse();
        assertThat(body.get("traceId").asText()).isNotBlank();
    }

    @Test
    void summaryEndpointIsAlsoIsolated() {
        String token = mintToken(DEMO_CUSTOMER_ID, "CUSTOMER");
        UUID otherCustomerId = UUID.randomUUID();

        HttpResponse<String> response = get(
            "/v1/customers/" + otherCustomerId + "/summary", token);

        assertThat(response.statusCode()).isEqualTo(403);
        JsonNode body = jsonBody(response);
        assertThat(body.get("title").asText()).isEqualTo("Forbidden");
    }

    @Test
    void missingTokenIsUnauthenticated() {
        HttpResponse<String> response = get(
            "/v1/customers/" + DEMO_CUSTOMER_ID + "/transactions");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).orElse(""))
            .isEqualTo("Bearer");
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .startsWith("application/problem+json");
        JsonNode body = jsonBody(response);
        assertThat(body.get("title").asText()).isEqualTo("Unauthenticated");
        assertThat(body.get("status").asInt()).isEqualTo(401);
    }

    @Test
    void garbageTokenIsUnauthenticated() {
        HttpResponse<String> response = get(
            "/v1/customers/" + DEMO_CUSTOMER_ID + "/transactions", "not-a-jwt");

        assertThat(response.statusCode()).isEqualTo(401);
        JsonNode body = jsonBody(response);
        assertThat(body.get("title").asText()).isEqualTo("Unauthenticated");
    }
}
