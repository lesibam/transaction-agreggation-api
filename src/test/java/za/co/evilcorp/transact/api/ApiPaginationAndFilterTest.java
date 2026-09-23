package za.co.evilcorp.transact.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keyset pagination walk plus category/date filters over a directly-seeded
 * customer dataset (independent of the async ingestion pipeline).
 */
class ApiPaginationAndFilterTest extends AbstractIntegrationTest {

    private static final int TOTAL = 25;
    private static final int GROCERIES_COUNT = 9;

    private SeededCustomer seeded;
    private String token;
    private OffsetDateTime baseDate;
    private String basePath;

    @BeforeEach
    void seedDataset() {
        seeded = seedCustomerWithAccount();
        token = mintToken(seeded.customerId(), "CUSTOMER");
        basePath = "/v1/customers/" + seeded.customerId() + "/transactions";
        baseDate = OffsetDateTime.now().truncatedTo(ChronoUnit.HOURS).minusHours(2);

        for (int i = 0; i < TOTAL; i++) {
            String category;
            if (i % 3 == 0) {
                category = "GROCERIES";
            } else if (i % 3 == 1) {
                category = "FOOD_DELIVERY";
            } else {
                category = null;
            }
            seedTransaction(
                seeded.customerId(),
                seeded.accountId(),
                "PG-" + i + "-" + UUID.randomUUID(),
                i % 2 == 0 ? TransactionDirection.DEBIT : TransactionDirection.CREDIT,
                BigDecimal.valueOf(10 + i),
                "ZAR",
                category,
                baseDate.minusHours(i));
        }
    }

    @Test
    void walksAllPagesUntilExhaustedWithoutDuplicateRows() {
        Set<String> visitedIds = new LinkedHashSet<>();
        String cursor = null;
        boolean hasMore;
        int pages = 0;

        do {
            String url = basePath + "?limit=10"
                + (cursor != null ? "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8) : "");
            HttpResponse<String> response = get(url, token);
            assertThat(response.statusCode()).isEqualTo(200);

            JsonNode body = jsonBody(response);
            JsonNode data = body.get("data");
            assertThat(data.isArray()).isTrue();
            for (JsonNode item : data) {
                String id = item.get("id").asText();
                assertThat(visitedIds.add(id))
                    .as("duplicate transaction id across pages: %s", id)
                    .isTrue();
            }

            JsonNode meta = body.get("meta");
            hasMore = meta.get("hasMore").asBoolean();
            cursor = meta.get("nextCursor").isNull() ? null : meta.get("nextCursor").asText();
            if (hasMore) {
                assertThat(cursor).as("nextCursor required while hasMore=true").isNotBlank();
            } else {
                assertThat(cursor).isNull();
            }
            pages++;
            assertThat(pages).isLessThanOrEqualTo(10);
        } while (hasMore);

        assertThat(visitedIds).hasSize(TOTAL);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void metaContainsCompletenessAndFreshnessStructre() {
        HttpResponse<String> response = get(basePath + "?limit=1", token);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode meta = jsonBody(response).get("meta");
        assertThat(meta.get("completeness").asText()).isIn("COMPLETE", "PARTIAL");
        assertThat(meta.get("freshness").get("status").asText())
            .isIn("FRESH", "STALE", "VERY_STALE", "UNKNOWN");
        JsonNode sources = meta.get("freshness").get("sources");
        assertThat(sources.isArray()).isTrue();
        assertThat(sources.size()).isEqualTo(3);
        Set<String> sourceIds = new LinkedHashSet<>();
        sources.forEach(s -> sourceIds.add(s.get("source").asText()));
        assertThat(sourceIds).containsExactlyInAnyOrder("SOURCE_A", "SOURCE_B", "SOURCE_C");
        sources.forEach(s -> assertThat(s.get("status").asText()).isIn("AVAILABLE", "UNAVAILABLE"));
    }

    @Test
    void categoryFilterReducesResultsToMatchingCategory() {
        HttpResponse<String> response = get(basePath + "?category=GROCERIES&limit=100", token);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode data = jsonBody(response).get("data");
        assertThat(data.size()).isEqualTo(GROCERIES_COUNT);
        for (JsonNode item : data) {
            assertThat(item.get("category").get("code").asText()).isEqualTo("GROCERIES");
        }
    }

    @Test
    void directionFilterReturnsOnlyMatchingDirection() {
        HttpResponse<String> response = get(basePath + "?direction=CREDIT&limit=100", token);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode data = jsonBody(response).get("data");
        assertThat(data.size()).isGreaterThan(0);
        assertThat(data.size()).isLessThan(TOTAL);
        for (JsonNode item : data) {
            assertThat(item.get("direction").asText()).isEqualTo("CREDIT");
        }
    }

    @Test
    void dateRangeFilterReturnsOnlyTransactionsInsideWindow() {
        OffsetDateTime windowStart = baseDate.minusHours(4).minusMinutes(30);
        OffsetDateTime windowEnd = baseDate.plusHours(1);
        String url = basePath
            + "?startDate=" + URLEncoder.encode(windowStart.toString(), StandardCharsets.UTF_8)
            + "&endDate=" + URLEncoder.encode(windowEnd.toString(), StandardCharsets.UTF_8)
            + "&limit=100";

        HttpResponse<String> response = get(url, token);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode data = jsonBody(response).get("data");
        assertThat(data.size()).isEqualTo(5);
        for (JsonNode item : data) {
            OffsetDateTime itemDate = OffsetDateTime.parse(item.get("transactionDate").asText());
            assertThat(itemDate).isAfterOrEqualTo(windowStart.minusSeconds(1));
            assertThat(itemDate).isBeforeOrEqualTo(windowEnd.plusSeconds(1));
        }
    }

    @Test
    void amountFiltersBoundTheResultSet() {
        HttpResponse<String> response = get(basePath + "?minAmount=30&maxAmount=39&limit=100", token);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode data = jsonBody(response).get("data");
        assertThat(data.size()).isGreaterThan(0);
        for (JsonNode item : data) {
            BigDecimal amount = new BigDecimal(item.get("amount").get("value").asText());
            assertThat(amount).isGreaterThanOrEqualTo(new BigDecimal("30"));
            assertThat(amount).isLessThanOrEqualTo(new BigDecimal("39"));
        }
    }
}
