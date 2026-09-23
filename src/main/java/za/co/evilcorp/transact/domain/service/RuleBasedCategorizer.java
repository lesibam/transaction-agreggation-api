package za.co.evilcorp.transact.domain.service;

import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.Category;

import java.util.List;
import java.util.Map;

public class RuleBasedCategorizer implements TransactionCategorizer {

    public static final Integer CURRENT_VERSION = 1;
    public static final String DEFAULT_RULE_ID = "DEFAULT_UNCATEGORIZED";

    private record Rule(String keyword, String code, String ruleId) {
    }

    // Ordered: first match wins (e.g. UBER EATS must precede UBER).
    // Matching is case-insensitive "contains" against merchantName (if present) else description.
    private static final List<Rule> RULES = List.of(
        new Rule("WOOLWORTHS", "GROCERIES", "MERCHANT_MATCH_WOOLWORTHS"),
        new Rule("UBER EATS", "FOOD_DELIVERY", "MERCHANT_MATCH_UBER_EATS"),
        new Rule("UBER", "TRANSPORT", "MERCHANT_MATCH_UBER"),
        new Rule("NETFLIX", "ENTERTAINMENT", "MERCHANT_MATCH_NETFLIX"),
        new Rule("CHECKERS", "GROCERIES", "MERCHANT_MATCH_CHECKERS"),
        new Rule("SALARY", "INCOME", "MERCHANT_MATCH_SALARY")
    );

    @Override
    public Category categorize(CanonicalTransaction transaction) {
        String text = matchText(transaction);
        if (text != null) {
            String haystack = text.toUpperCase(java.util.Locale.ROOT);
            for (Rule rule : RULES) {
                if (haystack.contains(rule.keyword())) {
                    return category(rule.code(), rule.ruleId());
                }
            }
        }
        return category("UNCATEGORIZED", DEFAULT_RULE_ID);
    }

    private String matchText(CanonicalTransaction transaction) {
        String merchant = transaction.getMerchantName();
        if (merchant != null && !merchant.isBlank()) {
            return merchant;
        }
        return transaction.getDescription();
    }

    private Category category(String code, String ruleId) {
        return Category.builder()
            .code(code)
            .version(CURRENT_VERSION)
            .ruleId(ruleId)
            .build();
    }
}
