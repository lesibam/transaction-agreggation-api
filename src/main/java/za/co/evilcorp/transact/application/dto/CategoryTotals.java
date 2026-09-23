package za.co.evilcorp.transact.application.dto;

import java.math.BigDecimal;

public record CategoryTotals(String category, BigDecimal amount, String currency, long transactionCount) {
}
