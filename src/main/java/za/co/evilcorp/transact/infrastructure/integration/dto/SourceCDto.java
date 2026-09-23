package za.co.evilcorp.transact.infrastructure.integration.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class SourceCDto {
    private String tx_id;
    private BigDecimal tx_amount;
    private String tx_currency;
    private String tx_desc;
    private String merchantName;
    private OffsetDateTime tx_date;
}
