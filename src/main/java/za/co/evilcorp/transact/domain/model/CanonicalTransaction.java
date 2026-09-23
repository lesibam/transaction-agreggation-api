package za.co.evilcorp.transact.domain.model;

import lombok.extern.jackson.Jacksonized;
import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Value
@Jacksonized
@Builder
public class CanonicalTransaction {

    public static final String NORMALIZATION_VERSION = "1";

    UUID id;
    UUID accountId;
    BigDecimal amount;
    String currency;
    TransactionDirection direction;
    OffsetDateTime transactionDate;
    OffsetDateTime postedAt;
    String description;
    String merchantName;
    String categoryCode;
    Integer categoryVersion;
    String ruleId;
    String normalizationVersion;
    String sourceId;
    String sourceTransactionId;
    OffsetDateTime ingestedAt;
}
