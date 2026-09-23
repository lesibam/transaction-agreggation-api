package za.co.evilcorp.transact.application.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TransactionView(
        UUID id,
        MoneyValue amount,
        String direction,
        OffsetDateTime transactionDate,
        String description,
        String merchantName,
        String categoryCode,
        SourceRef source) {
}
