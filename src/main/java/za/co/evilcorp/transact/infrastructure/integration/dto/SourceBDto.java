package za.co.evilcorp.transact.infrastructure.integration.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class SourceBDto {
    private String transactionReference;
    private BigDecimal value;
    private String currencyCode;
    private String merchantInfo;
    private String merchantName;
    private OffsetDateTime timestamp;
}
