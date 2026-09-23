package za.co.evilcorp.transact.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.evilcorp.transact.support.AbstractIntegrationTest;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /v1/admin/sources is restricted to ROLE_ADMIN.
 * SecurityConfig's access-denied handler titles this "Access Denied"
 * (distinct from the GlobalExceptionHandler "Forbidden" for validator denials).
 */
class AdminSourcesEndpointTest extends AbstractIntegrationTest {

    private String adminToken;
    private String customerToken;

    @BeforeEach
    void mintTokens() {
        adminToken = mintToken(DEMO_CUSTOMER_ID, "ADMIN");
        customerToken = mintToken(DEMO_CUSTOMER_ID, "CUSTOMER");
    }

    @Test
    void customerRoleIsForbidden() {
        HttpResponse<String> response = get("/v1/admin/sources", customerToken);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .startsWith("application/problem+json");
        JsonNode body = jsonBody(response);
        assertThat(body.get("title").asText()).isEqualTo("Access Denied");
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("detail").asText()).contains("permission");
    }

    @Test
    void anonymousIsUnauthenticated() {
        HttpResponse<String> response = get("/v1/admin/sources");

        assertThat(response.statusCode()).isEqualTo(401);
        JsonNode body = jsonBody(response);
        assertThat(body.get("title").asText()).isEqualTo("Unauthenticated");
    }

    @Test
    void adminRoleSeesAllSourcesWithStatus() {
        Awaitility.await()
            .atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> {
                HttpResponse<String> response = get("/v1/admin/sources", adminToken);
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode sources = jsonBody(response).get("sources");
                assertThat(sources.isArray()).isTrue();
                assertThat(sources.size()).isEqualTo(3);
                Set<String> ids = new LinkedHashSet<>();
                for (JsonNode source : sources) {
                    ids.add(source.get("id").asText());
                    assertThat(source.has("status")).isTrue();
                    assertThat(source.get("status").asText())
                        .isIn("INITIAL", "SUCCESS", "FAILED");
                }
                assertThat(ids).containsExactlyInAnyOrder("SOURCE_A", "SOURCE_B", "SOURCE_C");
            });
    }

    @Test
    void adminSourcesEventuallyReportSuccessAfterStartupIngestion() {
        Awaitility.await()
            .atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> {
                HttpResponse<String> response = get("/v1/admin/sources", adminToken);
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode sources = jsonBody(response).get("sources");
                for (JsonNode source : sources) {
                    assertThat(source.get("status").asText()).isEqualTo("SUCCESS");
                }
            });
    }
}
