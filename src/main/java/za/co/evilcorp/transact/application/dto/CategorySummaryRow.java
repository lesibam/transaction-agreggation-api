package za.co.evilcorp.transact.application.dto;

import java.math.BigDecimal;

public interface CategorySummaryRow {

    String getCategory();

    String getCurrency();

    BigDecimal getAmount();

    Long getTransactionCount();
}
