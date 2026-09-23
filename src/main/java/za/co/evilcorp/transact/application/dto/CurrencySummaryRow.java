package za.co.evilcorp.transact.application.dto;

import java.math.BigDecimal;

public interface CurrencySummaryRow {

    String getCurrency();

    BigDecimal getTotalDebit();

    BigDecimal getTotalCredit();
}
