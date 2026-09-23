package za.co.evilcorp.transact.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record SummaryDto(
        List<CurrencySummary> summaries,
        List<CategorySummary> categoryBreakdown,
        MetaDto meta) {

    public record CurrencySummary(String currency, BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal netFlow) {
    }

    public record CategorySummary(String category, BigDecimal amount, String currency, long transactionCount) {
    }
}
