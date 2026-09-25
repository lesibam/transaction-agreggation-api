package za.co.evilcorp.transact.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import za.co.evilcorp.transact.support.AbstractIntegrationTest;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * app.ui.enabled=false / app.docs.enabled=false (a production deployment
 * that wants neither reachable, per ADR-013/014) must mean a genuine 404 -
 * UiResourceConfig/DocsResourceConfig never register a handler, not a
 * hidden link a determined caller could still reach.
 *
 * Also pins the regression this feature's own spring.web.resources.
 * add-mappings=false introduced and GlobalExceptionHandler.
 * handleNoHandlerFound fixed: with Boot's default catch-all resource
 * handler off, an unrelated unmapped route (/v1/does-not-exist) used to
 * throw NoResourceFoundException (handled, 404) but now throws the
 * differently-typed NoHandlerFoundException instead - both must still
 * produce the same RFC 7807 404, not a 500.
 */
@TestPropertySource(properties = {"app.ui.enabled=false", "app.docs.enabled=false"})
class DocsAndUiDisabledTest extends AbstractIntegrationTest {

    @Test
    void dashboardIsNotFoundWhenDisabled() {
        assertProblemNotFound(get("/ui/"));
        assertProblemNotFound(get("/ui/index.html"));
    }

    @Test
    void docsViewerIsNotFoundWhenDisabled() {
        assertProblemNotFound(get("/docs/"));
        assertProblemNotFound(get("/docs/openapi.yaml"));
        assertProblemNotFound(get("/docs-assets/5.32.15/swagger-ui.css"));
    }

    @Test
    void unrelatedUnmappedRouteIsStillACleanNotFound() {
        // The regression this test class exists to pin: see class Javadoc.
        assertProblemNotFound(get("/v1/does-not-exist", mintToken(DEMO_CUSTOMER_ID, "CUSTOMER")));
    }

    @Test
    void apiItselfIsUnaffected() {
        String token = mintToken(DEMO_CUSTOMER_ID, "CUSTOMER");
        HttpResponse<String> response = get("/v1/customers/" + DEMO_CUSTOMER_ID + "/transactions?limit=1", token);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private void assertProblemNotFound(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .startsWith("application/problem+json");
        JsonNode body = jsonBody(response);
        assertThat(body.get("status").asInt()).isEqualTo(404);
        assertThat(body.get("type").asText()).contains("not-found");
    }
}
