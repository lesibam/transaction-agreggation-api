package za.co.evilcorp.transact.infrastructure.integration.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Source C shape (snake_case): {tx_id, tx_amount (signed), tx_currency,
 * tx_desc, merchantName, tx_date}. Sign-magnitude convention like A and B.
 */
@Component
public class SourceCNormalizer implements SourceNormalizer {

    public static final String KEY = "source-c-v1";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public CanonicalTransaction normalize(JsonNode raw, String sourceId) {
        BigDecimal amount = JsonFields.decimal(raw, "tx_amount");
        return CanonicalTransaction.builder()
            .sourceId(sourceId)
            .sourceTransactionId(JsonFields.text(raw, "tx_id"))
            .amount(amount == null ? null : amount.abs())
            .currency(JsonFields.text(raw, "tx_currency"))
            .direction(amount == null ? null
                : amount.compareTo(BigDecimal.ZERO) < 0
                    ? TransactionDirection.DEBIT
                    : TransactionDirection.CREDIT)
            .description(JsonFields.text(raw, "tx_desc"))
            .merchantName(JsonFields.text(raw, "merchantName"))
            .transactionDate(JsonFields.dateTime(raw, "tx_date"))
            .normalizationVersion(CanonicalTransaction.NORMALIZATION_VERSION)
            .ingestedAt(OffsetDateTime.now())
            .build();
    }
}
