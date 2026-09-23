package za.co.evilcorp.transact.infrastructure.integration.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.infrastructure.integration.dto.SourceCDto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class SourceCAdapter implements TransactionSource {

    @Override
    public String getSourceId() {
        return "SOURCE_C";
    }

    @Override
    public SourceResponse fetchTransactions(Optional<String> cursor) {
        log.info("Fetching transactions from Source C with cursor: {}", cursor);

        // Mocked payload. A production adapter would call the source REST API via
        // WebClient with explicit connect/read timeouts and bounded retries;
        // no HTTP client is used here.
        List<SourceCDto> rawData = simulateApiCall(cursor);

        List<CanonicalTransaction> normalized = rawData.stream()
            .map(this::normalize)
            .toList();

        return new SourceResponse(normalized, SyncCursor.next(cursor));
    }

    private CanonicalTransaction normalize(SourceCDto dto) {
        BigDecimal amount = dto.getTx_amount();
        return CanonicalTransaction.builder()
            .sourceId(getSourceId())
            .sourceTransactionId(dto.getTx_id())
            .amount(amount == null ? null : amount.abs())
            .currency(dto.getTx_currency())
            .direction(amount == null ? null
                : amount.compareTo(BigDecimal.ZERO) < 0
                    ? TransactionDirection.DEBIT
                    : TransactionDirection.CREDIT)
            .description(dto.getTx_desc())
            .merchantName(dto.getMerchantName())
            .transactionDate(dto.getTx_date())
            .normalizationVersion(CanonicalTransaction.NORMALIZATION_VERSION)
            .ingestedAt(OffsetDateTime.now())
            .build();
    }

    private List<SourceCDto> simulateApiCall(Optional<String> cursor) {
        SourceCDto tx1 = new SourceCDto();
        tx1.setTx_id("C-999");
        tx1.setTx_amount(new BigDecimal("-12.50"));
        tx1.setTx_currency("ZAR");
        tx1.setTx_desc("Online Store C");
        tx1.setMerchantName("Netflix");
        tx1.setTx_date(OffsetDateTime.now().minusMinutes(30));

        return List.of(tx1);
    }
}
