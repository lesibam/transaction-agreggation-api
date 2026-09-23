package za.co.evilcorp.transact.application.dto;

import java.math.BigDecimal;

public record MoneyValue(BigDecimal value, String currency) {
}
