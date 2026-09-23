package za.co.evilcorp.transact.infrastructure.integration.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class SourceADto {
    private String id;
    private BigDecimal amount;
    private String currency;
    private String description;
    private String merchantName;
    private OffsetDateTime date;
}
