package za.co.evilcorp.transact.infrastructure.integration.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Source B shape: {transactionReference, value (signed), currencyCode,
 * merchantInfo (description), merchantName, timestamp}. Sign-magnitude
 * convention like Source A, different field names.
 */
@Component
public class SourceBNormalizer implements SourceNormalizer {

    public static final String KEY = "source-b-v1";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public CanonicalTransaction normalize(JsonNode raw, String sourceId) {
        BigDecimal value = JsonFields.decimal(raw, "value");
        return CanonicalTransaction.builder()
            .sourceId(sourceId)
            .sourceTransactionId(JsonFields.text(raw, "transactionReference"))
            .amount(value == null ? null : value.abs())
            .currency(JsonFields.text(raw, "currencyCode"))
            .direction(value == null ? null
                : value.compareTo(BigDecimal.ZERO) < 0
                    ? TransactionDirection.DEBIT
                    : TransactionDirection.CREDIT)
            .description(JsonFields.text(raw, "merchantInfo"))
            .merchantName(JsonFields.text(raw, "merchantName"))
            .transactionDate(JsonFields.dateTime(raw, "timestamp"))
            .normalizationVersion(CanonicalTransaction.NORMALIZATION_VERSION)
            .ingestedAt(OffsetDateTime.now())
            .build();
    }
}
