package za.co.evilcorp.transact.infrastructure.integration.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.infrastructure.integration.dto.SourceBDto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class SourceBAdapter implements TransactionSource {

    @Override
    public String getSourceId() {
        return "SOURCE_B";
    }

    @Override
    public SourceResponse fetchTransactions(Optional<String> cursor) {
        log.info("Fetching transactions from Source B with cursor: {}", cursor);

        // Mocked payload. A production adapter would call the source REST API via
        // WebClient with explicit connect/read timeouts and bounded retries;
        // no HTTP client is used here.
        List<SourceBDto> rawData = simulateApiCall(cursor);

        List<CanonicalTransaction> normalized = rawData.stream()
            .map(this::normalize)
            .toList();

        return new SourceResponse(normalized, SyncCursor.next(cursor));
    }

    private CanonicalTransaction normalize(SourceBDto dto) {
        // Source B provides a signed value: direction is derived from the sign,
        // amount is stored as an absolute value.
        BigDecimal value = dto.getValue();
        return CanonicalTransaction.builder()
            .sourceId(getSourceId())
            .sourceTransactionId(dto.getTransactionReference())
            .amount(value == null ? null : value.abs())
            .currency(dto.getCurrencyCode())
            .direction(value == null ? null
                : value.compareTo(BigDecimal.ZERO) < 0
                    ? TransactionDirection.DEBIT
                    : TransactionDirection.CREDIT)
            .description(dto.getMerchantInfo())
            .merchantName(dto.getMerchantName())
            .transactionDate(dto.getTimestamp())
            .normalizationVersion(CanonicalTransaction.NORMALIZATION_VERSION)
            .ingestedAt(OffsetDateTime.now())
            .build();
    }

    private List<SourceBDto> simulateApiCall(Optional<String> cursor) {
        SourceBDto tx1 = new SourceBDto();
        tx1.setTransactionReference("B-REF-001");
        tx1.setValue(new BigDecimal("-45.00"));
        tx1.setCurrencyCode("ZAR");
        tx1.setMerchantInfo("Coffee Shop B");
        tx1.setMerchantName("STARBUCKS");
        tx1.setTimestamp(OffsetDateTime.now().minusHours(5));

        SourceBDto tx2 = new SourceBDto();
        tx2.setTransactionReference("B-REF-002");
        tx2.setValue(new BigDecimal("-120.50"));
        tx2.setCurrencyCode("ZAR");
        tx2.setMerchantInfo("Food delivery");
        tx2.setMerchantName("UBER EATS");
        tx2.setTimestamp(OffsetDateTime.now().minusHours(3));

        return List.of(tx1, tx2);
    }
}
