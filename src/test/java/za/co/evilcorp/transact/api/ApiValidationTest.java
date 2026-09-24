package za.co.evilcorp.transact.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.evilcorp.transact.support.AbstractIntegrationTest;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Request validation failures must surface as application/problem+json with
 * RFC 7807 fields (type, title, status, instance, traceId).
 */
class ApiValidationTest extends AbstractIntegrationTest {

    private String token;
    private String path;

    @BeforeEach
    void mintTokenForDemoCustomer() {
        token = mintToken(DEMO_CUSTOMER_ID, "CUSTOMER");
        path = "/v1/customers/" + DEMO_CUSTOMER_ID + "/transactions";
    }

    private void assertProblemDetail(HttpResponse<String> response, int expectedStatus) {
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .startsWith("application/problem+json");

        JsonNode body = jsonBody(response);
        assertThat(body.get("type").asText()).contains("invalid-request");
        assertThat(body.get("title").asText()).isEqualTo("Bad Request");
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("instance").asText()).isEqualTo(path);
        assertThat(body.has("traceId")).isTrue();
        assertThat(body.get("traceId").isNull()).isFalse();
        assertThat(body.get("traceId").asText()).isNotBlank();
        assertThat(body.get("detail").asText()).isNotBlank();
    }

    @Test
    void limitBelowMinimumIsRejected() {
        assertProblemDetail(get(path + "?limit=0", token), 400);
    }

    @Test
    void limitAboveMaximumIsRejected() {
        assertProblemDetail(get(path + "?limit=1000", token), 400);
    }

    @Test
    void nonBase64CursorIsRejected() {
        assertProblemDetail(get(path + "?cursor=!!!not-a-valid-cursor!!!", token), 400);
    }

    @Test
    void nonBase64EvenIfDecodableButMalformedPayloadIsRejected() {
        String cursorOnlyGibberish = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("definitely-not-a-cursor".getBytes());
        assertProblemDetail(get(path + "?cursor=" + cursorOnlyGibberish, token), 400);
    }

    @Test
    void unknownDirectionValueIsRejected() {
        assertProblemDetail(get(path + "?direction=BOGUS_DIRECTION", token), 400);
    }

    @Test
    void invalidStartDateIsRejected() {
        assertProblemDetail(get(path + "?startDate=not-a-date", token), 400);
    }

    @Test
    void validRequestPassesValidation() {
        HttpResponse<String> response = get(path + "?limit=1", token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .startsWith("application/json");
    }

    // These two guard the fix in GlobalExceptionHandler (M-04): before it, the
    // Exception.class catch-all turned both cases into a generic 500 instead
    // of the correct 405/404, because it matched before Boot's own handling
    // for these specific exception types ever got a chance to run.
    @Test
    void wrongHttpMethodReturnsProblemDetail405() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .startsWith("application/problem+json");
        JsonNode body = jsonBody(response);
        assertThat(body.get("type").asText()).contains("method-not-allowed");
        assertThat(body.get("status").asInt()).isEqualTo(405);
    }

    @Test
    void unknownRouteReturnsProblemDetail404() {
        HttpResponse<String> response = get("/v1/this-route-does-not-exist", token);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .startsWith("application/problem+json");
        JsonNode body = jsonBody(response);
        assertThat(body.get("type").asText()).contains("not-found");
        assertThat(body.get("status").asInt()).isEqualTo(404);
    }
}
