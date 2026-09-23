package za.co.evilcorp.transact.api.dto;

import java.math.BigDecimal;

public record MoneyDto(BigDecimal value, String currency) {
}
