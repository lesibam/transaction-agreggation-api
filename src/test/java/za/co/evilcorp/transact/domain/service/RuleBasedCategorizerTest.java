package za.co.evilcorp.transact.domain.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.Category;
import za.co.evilcorp.transact.domain.model.TransactionDirection;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedCategorizerTest {

    private final RuleBasedCategorizer categorizer = new RuleBasedCategorizer();

    private static CanonicalTransaction tx(String merchantName, String description) {
        return CanonicalTransaction.builder()
            .sourceId("SOURCE_A")
            .sourceTransactionId("T-1")
            .amount(BigDecimal.TEN)
            .currency("ZAR")
            .direction(TransactionDirection.DEBIT)
            .transactionDate(OffsetDateTime.now())
            .merchantName(merchantName)
            .description(description)
            .normalizationVersion("1")
            .ingestedAt(OffsetDateTime.now())
            .build();
    }

    @ParameterizedTest
    @CsvSource({
        "Woolworths,            GROCERIES,      MERCHANT_MATCH_WOOLWORTHS",
        "WOOLWORTHS MARKET,      GROCERIES,      MERCHANT_MATCH_WOOLWORTHS",
        "UBER EATS,              FOOD_DELIVERY,  MERCHANT_MATCH_UBER_EATS",
        "Uber Eats,              FOOD_DELIVERY,  MERCHANT_MATCH_UBER_EATS",
        "UBER,                   TRANSPORT,      MERCHANT_MATCH_UBER",
        "NETFLIX,                ENTERTAINMENT,  MERCHANT_MATCH_NETFLIX",
        "Netflix.com,            ENTERTAINMENT,  MERCHANT_MATCH_NETFLIX",
        "CHECKERS,               GROCERIES,      MERCHANT_MATCH_CHECKERS",
        "SALARY PAYMENT,         INCOME,         MERCHANT_MATCH_SALARY"
    })
    void merchantRulesMatchExpectedCategory(String merchant, String expectedCode, String expectedRuleId) {
        Category category = categorizer.categorize(tx(merchant, null));

        assertThat(category.getCode()).isEqualTo(expectedCode);
        assertThat(category.getRuleId()).isEqualTo(expectedRuleId);
        assertThat(category.getVersion()).isEqualTo(1);
    }

    @Test
    void unknownMerchantIsUncategorizedWithDefaultRule() {
        Category category = categorizer.categorize(tx("SOME RANDOM SHOP", null));

        assertThat(category.getCode()).isEqualTo("UNCATEGORIZED");
        assertThat(category.getRuleId()).isEqualTo(RuleBasedCategorizer.DEFAULT_RULE_ID);
        assertThat(category.getVersion()).isEqualTo(RuleBasedCategorizer.CURRENT_VERSION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"woolworths", "WoOlWoRtHs", "uber eats", "netflix", "salary"})
    void matchingIsCaseInsensitive(String merchant) {
        Category category = categorizer.categorize(tx(merchant, null));

        assertThat(category.getCode()).isNotEqualTo("UNCATEGORIZED");
        assertThat(category.getVersion()).isEqualTo(1);
    }

    @Test
    void uberEatsTakesPrecedenceOverUber() {
        Category category = categorizer.categorize(tx("UBER EATS", null));

        assertThat(category.getCode()).isEqualTo("FOOD_DELIVERY");
        assertThat(category.getRuleId()).isEqualTo("MERCHANT_MATCH_UBER_EATS");
    }

    @Test
    void merchantNameIsPreferredOverDescription() {
        Category category = categorizer.categorize(tx("UBER", "SALARY reimbursement"));

        assertThat(category.getCode()).isEqualTo("TRANSPORT");
        assertThat(category.getRuleId()).isEqualTo("MERCHANT_MATCH_UBER");
    }

    @Test
    void fallsBackToDescriptionWhenMerchantNameAbsent() {
        Category category = categorizer.categorize(tx(null, "Netflix subscription payment"));

        assertThat(category.getCode()).isEqualTo("ENTERTAINMENT");
        assertThat(category.getRuleId()).isEqualTo("MERCHANT_MATCH_NETFLIX");
    }

    @Test
    void fallsBackToDescriptionWhenMerchantNameBlank() {
        Category category = categorizer.categorize(tx("   ", "Salary deposit"));

        assertThat(category.getCode()).isEqualTo("INCOME");
    }

    @Test
    void nullDescriptionAndNullMerchantIsUncategorized() {
        Category category = categorizer.categorize(tx(null, null));

        assertThat(category.getCode()).isEqualTo("UNCATEGORIZED");
        assertThat(category.getRuleId()).isEqualTo("DEFAULT_UNCATEGORIZED");
    }

    @Test
    void versionIsAlwaysCurrentVersion() {
        assertThat(categorizer.categorize(tx("Woolworths", null)).getVersion())
            .isEqualTo(RuleBasedCategorizer.CURRENT_VERSION);
        assertThat(categorizer.categorize(tx(null, null)).getVersion())
            .isEqualTo(RuleBasedCategorizer.CURRENT_VERSION);
    }
}
