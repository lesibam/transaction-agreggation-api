package za.co.evilcorp.transact.infrastructure.integration.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.infrastructure.integration.dto.SourceADto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class SourceAAdapter implements TransactionSource {

    @Override
    public String getSourceId() {
        return "SOURCE_A";
    }

    @Override
    public SourceResponse fetchTransactions(Optional<String> cursor) {
        log.info("Fetching transactions from Source A with cursor: {}", cursor);

        // Mocked payload. A production adapter would call the source REST API via
        // WebClient with explicit connect/read timeouts and bounded retries;
        // no HTTP client is used here.
        List<SourceADto> rawData = simulateApiCall(cursor);

        List<CanonicalTransaction> normalized = rawData.stream()
            .map(this::normalize)
            .toList();

        return new SourceResponse(normalized, SyncCursor.next(cursor));
    }

    private CanonicalTransaction normalize(SourceADto dto) {
        BigDecimal amount = dto.getAmount();
        return CanonicalTransaction.builder()
            .sourceId(getSourceId())
            .sourceTransactionId(dto.getId())
            .amount(amount == null ? null : amount.abs())
            .currency(dto.getCurrency())
            .direction(amount == null ? null
                : amount.compareTo(BigDecimal.ZERO) < 0
                    ? TransactionDirection.DEBIT
                    : TransactionDirection.CREDIT)
            .description(dto.getDescription())
            .merchantName(dto.getMerchantName())
            .transactionDate(dto.getDate())
            .normalizationVersion(CanonicalTransaction.NORMALIZATION_VERSION)
            .ingestedAt(OffsetDateTime.now())
            .build();
    }

    private List<SourceADto> simulateApiCall(Optional<String> cursor) {
        SourceADto tx1 = new SourceADto();
        tx1.setId("A100");
        tx1.setAmount(new BigDecimal("-150.00"));
        tx1.setCurrency("ZAR");
        tx1.setDescription("Grocery Store A");
        tx1.setMerchantName("Woolworths");
        tx1.setDate(OffsetDateTime.now().minusDays(1));

        SourceADto tx2 = new SourceADto();
        tx2.setId("A101");
        tx2.setAmount(new BigDecimal("2000.00"));
        tx2.setCurrency("ZAR");
        tx2.setDescription("Salary Deposit");
        tx2.setMerchantName(null);
        tx2.setDate(OffsetDateTime.now().minusDays(2));

        return List.of(tx1, tx2);
    }
}
