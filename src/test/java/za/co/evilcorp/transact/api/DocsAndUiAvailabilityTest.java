package za.co.evilcorp.transact.api;

import org.junit.jupiter.api.Test;
import za.co.evilcorp.transact.support.AbstractIntegrationTest;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * app.ui.enabled and app.docs.enabled default to true (ADR-013, ADR-014) -
 * verifies the dashboard and docs viewer are actually reachable in that
 * default state. See DocsAndUiDisabledTest for the opposite: both toggles
 * off, and that /v1/** (and unrelated routing) is unaffected either way.
 *
 * The swagger-ui version segment below must stay in sync with pom.xml's
 * swagger-ui.version property (ADR-014's documented tradeoff) - a mismatch
 * fails this test with a 404, not silently.
 */
class DocsAndUiAvailabilityTest extends AbstractIntegrationTest {

    private static final String SWAGGER_UI_VERSION = "5.32.15";

    @Test
    void dashboardShellIsReachable() {
        assertOk(get("/ui/"));
        assertOk(get("/ui/index.html"));
        assertOk(get("/ui/styles.css"));
        assertOk(get("/ui/app.js"));
    }

    @Test
    void docsViewerAndSpecAreReachable() {
        assertOk(get("/docs/"));
        assertOk(get("/docs/openapi.yaml"));
        assertOk(get("/docs-assets/" + SWAGGER_UI_VERSION + "/swagger-ui-bundle.js"));
        assertOk(get("/docs-assets/" + SWAGGER_UI_VERSION + "/swagger-ui.css"));
    }

    @Test
    void neitherPathRequiresAuthentication() {
        // No Authorization header on any of the calls above, and they still
        // returned 200 - both are static shells with no data of their own
        // (see ADR-013/014); this pins that permitAll stays in place.
        assertOk(get("/ui/"));
        assertOk(get("/docs/"));
    }

    private void assertOk(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
