package za.co.evilcorp.transact.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.evilcorp.transact.api.dto.CategoryDto;
import za.co.evilcorp.transact.api.dto.FreshnessDto;
import za.co.evilcorp.transact.api.dto.MerchantDto;
import za.co.evilcorp.transact.api.dto.MetaDto;
import za.co.evilcorp.transact.api.dto.MoneyDto;
import za.co.evilcorp.transact.api.dto.SummaryDto;
import za.co.evilcorp.transact.api.dto.TransactionDto;
import za.co.evilcorp.transact.api.dto.TransactionResponseDto;
import za.co.evilcorp.transact.application.dto.FreshnessInfo;
import za.co.evilcorp.transact.application.dto.MetaInfo;
import za.co.evilcorp.transact.application.dto.SummaryResult;
import za.co.evilcorp.transact.application.dto.TransactionListResult;
import za.co.evilcorp.transact.application.dto.TransactionView;
import za.co.evilcorp.transact.application.service.TransactionQueryService;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.security.CustomerAccessValidator;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/v1/customers/{customerId}")
@Validated
public class TransactionController {

    private final TransactionQueryService queryService;
    private final CustomerAccessValidator customerAccessValidator;

    public TransactionController(TransactionQueryService queryService, CustomerAccessValidator customerAccessValidator) {
        this.queryService = queryService;
        this.customerAccessValidator = customerAccessValidator;
    }

    @GetMapping("/transactions")
    public ResponseEntity<TransactionResponseDto> getTransactions(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) TransactionDirection direction,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount) {
        customerAccessValidator.requireAccess(customerId);
        TransactionListResult result = queryService.getTransactions(
                customerId, limit, cursor, category, direction, startDate, endDate, minAmount, maxAmount);
        return ResponseEntity.ok(toResponse(result));
    }

    @GetMapping("/summary")
    public ResponseEntity<SummaryDto> getSummary(
            @PathVariable UUID customerId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        customerAccessValidator.requireAccess(customerId);
        SummaryResult result = queryService.getSummary(customerId, startDate, endDate);
        return ResponseEntity.ok(toSummaryResponse(result));
    }

    private TransactionResponseDto toResponse(TransactionListResult result) {
        return new TransactionResponseDto(
                result.data().stream().map(TransactionController::toDto).toList(),
                toMeta(result.meta()));
    }

    private SummaryDto toSummaryResponse(SummaryResult result) {
        return new SummaryDto(
                result.summaries().stream()
                        .map(s -> new SummaryDto.CurrencySummary(
                                s.currency(), s.totalDebit(), s.totalCredit(), s.netFlow()))
                        .toList(),
                result.categoryBreakdown().stream()
                        .map(c -> new SummaryDto.CategorySummary(
                                c.category(), c.amount(), c.currency(), c.transactionCount()))
                        .toList(),
                toMeta(result.meta()));
    }

    private static TransactionDto toDto(TransactionView view) {
        return new TransactionDto(
                view.id(),
                new MoneyDto(view.amount().value(), view.amount().currency()),
                view.direction(),
                view.transactionDate(),
                view.description(),
                new MerchantDto(view.merchantName()),
                new CategoryDto(view.categoryCode()),
                new TransactionDto.SourceDto(view.source().provider(), view.source().transactionId()));
    }

    private static MetaDto toMeta(MetaInfo meta) {
        return new MetaDto(meta.nextCursor(), meta.hasMore(), meta.completeness(), toFreshness(meta.freshness()));
    }

    private static FreshnessDto toFreshness(FreshnessInfo freshness) {
        return new FreshnessDto(
                freshness.status(),
                freshness.generatedAt(),
                freshness.sources().stream()
                        .map(s -> new FreshnessDto.SourceFreshness(s.source(), s.lastSync(), s.status()))
                        .toList());
    }
}
