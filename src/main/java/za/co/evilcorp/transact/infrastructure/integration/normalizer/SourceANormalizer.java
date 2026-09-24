package za.co.evilcorp.transact.infrastructure.integration.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Source A shape: {id, amount (signed), currency, description, merchantName, date}.
 * Sign-magnitude convention: amount is stored absolute, direction inferred from sign.
 */
@Component
public class SourceANormalizer implements SourceNormalizer {

    public static final String KEY = "source-a-v1";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public CanonicalTransaction normalize(JsonNode raw, String sourceId) {
        BigDecimal amount = JsonFields.decimal(raw, "amount");
        return CanonicalTransaction.builder()
            .sourceId(sourceId)
            .sourceTransactionId(JsonFields.text(raw, "id"))
            .amount(amount == null ? null : amount.abs())
            .currency(JsonFields.text(raw, "currency"))
            .direction(amount == null ? null
                : amount.compareTo(BigDecimal.ZERO) < 0
                    ? TransactionDirection.DEBIT
                    : TransactionDirection.CREDIT)
            .description(JsonFields.text(raw, "description"))
            .merchantName(JsonFields.text(raw, "merchantName"))
            .transactionDate(JsonFields.dateTime(raw, "date"))
            .normalizationVersion(CanonicalTransaction.NORMALIZATION_VERSION)
            .ingestedAt(OffsetDateTime.now())
            .build();
    }
}
