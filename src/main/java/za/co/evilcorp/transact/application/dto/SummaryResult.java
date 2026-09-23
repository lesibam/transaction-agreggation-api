package za.co.evilcorp.transact.application.dto;

import java.util.List;

public record SummaryResult(
        List<CurrencyTotals> summaries,
        List<CategoryTotals> categoryBreakdown,
        MetaInfo meta) {
}
