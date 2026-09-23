package za.co.evilcorp.transact.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TransactionDto(
        UUID id,
        MoneyDto amount,
        String direction,
        OffsetDateTime transactionDate,
        String description,
        MerchantDto merchant,
        CategoryDto category,
        SourceDto source) {

    public record SourceDto(String provider, String transactionId) {
    }
}
