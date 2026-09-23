package za.co.evilcorp.transact.application.dto;

import java.math.BigDecimal;

public record CurrencyTotals(String currency, BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal netFlow) {
}
