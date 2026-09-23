package za.co.evilcorp.transact.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /v1/customers/{id}/summary: per-currency totals stay separate
 * (ZAR and USD are never merged) and category breakdown is present.
 */
class ApiSummaryCurrencyTest extends AbstractIntegrationTest {

    private SeededCustomer seeded;
    private String token;
    private String summaryPath;
    private String runId;

    @BeforeEach
    void seedMultiCurrencyData() {
        seeded = seedCustomerWithAccount();
        token = mintToken(seeded.customerId(), "CUSTOMER");
        summaryPath = "/v1/customers/" + seeded.customerId() + "/summary";
        runId = UUID.randomUUID().toString();
        OffsetDateTime when = OffsetDateTime.now().minusDays(1);

        seedTransaction(seeded.customerId(), seeded.accountId(), "SC-1-" + runId,
            TransactionDirection.DEBIT, new BigDecimal("100.00"), "ZAR", "GROCERIES", when);
        seedTransaction(seeded.customerId(), seeded.accountId(), "SC-2-" + runId,
            TransactionDirection.DEBIT, new BigDecimal("50.00"), "ZAR", "FOOD_DELIVERY", when);
        seedTransaction(seeded.customerId(), seeded.accountId(), "SC-3-" + runId,
            TransactionDirection.CREDIT, new BigDecimal("200.00"), "ZAR", "INCOME", when);
        seedTransaction(seeded.customerId(), seeded.accountId(), "SC-4-" + runId,
            TransactionDirection.DEBIT, new BigDecimal("10.00"), "USD", "ENTERTAINMENT", when);
        seedTransaction(seeded.customerId(), seeded.accountId(), "SC-5-" + runId,
            TransactionDirection.CREDIT, new BigDecimal("5.00"), "USD", "INCOME", when);
    }

    private JsonNode summaryFor(String currency) {
        for (JsonNode row : root().get("summaries")) {
            if (currency.equals(row.get("currency").asText())) {
                return row;
            }
        }
        throw new AssertionError("No summary row for currency " + currency);
    }

    private JsonNode rootCache;

    private JsonNode root() {
        if (rootCache == null) {
            HttpResponse<String> response = get(summaryPath, token);
            assertThat(response.statusCode()).isEqualTo(200);
            rootCache = jsonBody(response);
        }
        return rootCache;
    }

    @Test
    void returnsSeparateTotalsPerCurrency() {
        JsonNode summaries = root().get("summaries");
        assertThat(summaries.isArray()).isTrue();
        assertThat(summaries.size()).isEqualTo(2);

        JsonNode zar = summaryFor("ZAR");
        assertThat(zar.get("totalDebit").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(zar.get("totalCredit").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(zar.get("netFlow").decimalValue()).isEqualByComparingTo("50.00");

        JsonNode usd = summaryFor("USD");
        assertThat(usd.get("totalDebit").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(usd.get("totalCredit").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(usd.get("netFlow").decimalValue()).isEqualByComparingTo("-5.00");
    }

    @Test
    void currenciesAreNotMerged() {
        JsonNode summaries = root().get("summaries");
        assertThat(summaries.size()).isEqualTo(2);

        BigDecimal zarDebit = summaryFor("ZAR").get("totalDebit").decimalValue();
        BigDecimal usdDebit = summaryFor("USD").get("totalDebit").decimalValue();

        assertThat(zarDebit).isEqualByComparingTo("150.00");
        assertThat(usdDebit).isEqualByComparingTo("10.00");
        assertThat(summaries.size()).isEqualTo(2);
    }

    @Test
    void categoryBreakdownGroupsByCategoryAndCurrency() {
        JsonNode breakdown = root().get("categoryBreakdown");
        assertThat(breakdown.isArray()).isTrue();
        assertThat(breakdown.size()).isGreaterThanOrEqualTo(4);

        JsonNode groceriesZar = null;
        for (JsonNode row : breakdown) {
            if ("GROCERIES".equals(row.get("category").asText())
                && "ZAR".equals(row.get("currency").asText())) {
                groceriesZar = row;
            }
        }
        assertThat(groceriesZar).isNotNull();
        assertThat(groceriesZar.get("amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(groceriesZar.get("transactionCount").asLong()).isEqualTo(1);
    }

    @Test
    void metaIsPresentWithFreshness() {
        JsonNode meta = root().get("meta");
        assertThat(meta.get("completeness").asText()).isIn("COMPLETE", "PARTIAL");
        assertThat(meta.get("freshness").get("status").asText())
            .isIn("FRESH", "STALE", "VERY_STALE", "UNKNOWN");
        assertThat(meta.get("hasMore").asBoolean()).isFalse();
        assertThat(meta.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void summaryOfIsolatedSeededCustomerDoesNotIncludeDemoData() {
        HttpResponse<String> response = get(summaryPath, token);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = jsonBody(response);

        assertThat(body.get("summaries").size()).isEqualTo(2);
        for (JsonNode row : body.get("summaries")) {
            String currency = row.get("currency").asText();
            assertThat(currency).isIn("ZAR", "USD");
        }
    }

    @Test
    void summaryRequiresAuthentication() {
        HttpResponse<String> response = get(summaryPath);
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void summaryForbiddenForOtherCustomer() {
        String otherToken = mintToken(UUID.randomUUID(), "CUSTOMER");
        HttpResponse<String> response = get(summaryPath, otherToken);
        assertThat(response.statusCode()).isEqualTo(403);
    }
}
