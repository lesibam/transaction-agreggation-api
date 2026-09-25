package za.co.evilcorp.transact.application.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import za.co.evilcorp.transact.application.dto.CategorySummaryRow;
import za.co.evilcorp.transact.application.dto.CategoryTotals;
import za.co.evilcorp.transact.application.dto.CurrencySummaryRow;
import za.co.evilcorp.transact.application.dto.CurrencyTotals;
import za.co.evilcorp.transact.application.dto.FreshnessInfo;
import za.co.evilcorp.transact.application.dto.MetaInfo;
import za.co.evilcorp.transact.application.dto.MoneyValue;
import za.co.evilcorp.transact.application.dto.SourceFreshnessInfo;
import za.co.evilcorp.transact.application.dto.SourceRef;
import za.co.evilcorp.transact.application.dto.SourceStatusResult;
import za.co.evilcorp.transact.application.dto.SummaryResult;
import za.co.evilcorp.transact.application.dto.TransactionListResult;
import za.co.evilcorp.transact.application.dto.TransactionView;
import za.co.evilcorp.transact.config.SourceRegistryProperties;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.infrastructure.persistence.entity.SourceSyncStateEntity;
import za.co.evilcorp.transact.infrastructure.persistence.entity.TransactionEntity;
import za.co.evilcorp.transact.infrastructure.persistence.repository.SourceSyncStateRepository;
import za.co.evilcorp.transact.infrastructure.persistence.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TransactionQueryService {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String COMPLETE = "COMPLETE";
    private static final String PARTIAL = "PARTIAL";
    private static final String AVAILABLE = "AVAILABLE";
    private static final String UNAVAILABLE = "UNAVAILABLE";
    private static final String FRESH = "FRESH";
    private static final String STALE = "STALE";
    private static final String VERY_STALE = "VERY_STALE";
    private static final String UNKNOWN = "UNKNOWN";
    private static final List<String> FRESHNESS_SEVERITY = List.of(FRESH, STALE, VERY_STALE, UNKNOWN);
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    private final TransactionRepository transactionRepository;
    private final SourceSyncStateRepository syncStateRepository;
    private final List<String> configuredSourceIds;
    private final long freshSeconds;
    private final long staleSeconds;

    public TransactionQueryService(
            TransactionRepository transactionRepository,
            SourceSyncStateRepository syncStateRepository,
            SourceRegistryProperties sourceRegistry,
            @Value("${app.freshness.fresh-seconds:300}") long freshSeconds,
            @Value("${app.freshness.stale-seconds:1800}") long staleSeconds) {
        this.transactionRepository = transactionRepository;
        this.syncStateRepository = syncStateRepository;
        // ALL registered sources (enabled or not): a disabled source must show up
        // as UNKNOWN freshness / PARTIAL completeness, never be hidden (ADR-011).
        this.configuredSourceIds = sourceRegistry.allIds();
        this.freshSeconds = freshSeconds;
        this.staleSeconds = staleSeconds;
    }

    public TransactionListResult getTransactions(
            UUID customerId,
            int limit,
            String cursor,
            String category,
            TransactionDirection direction,
            String startDate,
            String endDate,
            BigDecimal minAmount,
            BigDecimal maxAmount) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }

        OffsetDateTime cursorDate = null;
        UUID cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            KeysetCursor.Parts parts = KeysetCursor.decode(cursor.trim());
            cursorDate = parts.transactionDate();
            cursorId = parts.id();
        }

        String normalizedCategory = (category == null || category.isBlank()) ? null : category.trim();
        String normalizedDirection = direction == null ? null : direction.name();

        List<TransactionEntity> rows = transactionRepository.searchByCustomerKeyset(
                customerId,
                normalizedCategory,
                normalizedDirection,
                parseStart(startDate),
                parseEnd(endDate),
                minAmount,
                maxAmount,
                cursorDate,
                cursorId,
                limit + 1);

        boolean hasMore = rows.size() > limit;
        List<TransactionEntity> page = hasMore ? List.copyOf(rows.subList(0, limit)) : rows;
        List<TransactionView> data = page.stream().map(this::toView).toList();

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            TransactionEntity last = page.get(page.size() - 1);
            nextCursor = KeysetCursor.encode(last.getTransactionDate(), last.getId());
        }

        return new TransactionListResult(data, buildMeta(nextCursor, hasMore));
    }

    public SummaryResult getSummary(UUID customerId, String startDate, String endDate) {
        OffsetDateTime start = parseStart(startDate);
        OffsetDateTime end = parseEnd(endDate);

        List<CurrencyTotals> summaries = transactionRepository.summarizeByCurrency(customerId, start, end).stream()
                .map(this::toCurrencyTotals)
                .toList();

        List<CategoryTotals> categories = transactionRepository.summarizeByCategory(customerId, start, end).stream()
                .map(this::toCategoryTotals)
                .toList();

        return new SummaryResult(summaries, categories, buildMeta(null, false));
    }

    public List<SourceStatusResult> getSourceStatuses() {
        OffsetDateTime now = OffsetDateTime.now();
        return syncStateRepository.findAll().stream()
                .sorted(Comparator.comparing(SourceSyncStateEntity::getSourceId))
                .map(state -> toSourceStatus(state, now))
                .toList();
    }

    private CurrencyTotals toCurrencyTotals(CurrencySummaryRow row) {
        BigDecimal debit = row.getTotalDebit() == null ? BigDecimal.ZERO : row.getTotalDebit();
        BigDecimal credit = row.getTotalCredit() == null ? BigDecimal.ZERO : row.getTotalCredit();
        long debitCount = row.getDebitCount() == null ? 0L : row.getDebitCount();
        long creditCount = row.getCreditCount() == null ? 0L : row.getCreditCount();
        return new CurrencyTotals(row.getCurrency(), debit, credit, credit.subtract(debit), debitCount, creditCount);
    }

    private CategoryTotals toCategoryTotals(CategorySummaryRow row) {
        BigDecimal amount = row.getAmount() == null ? BigDecimal.ZERO : row.getAmount();
        long count = row.getTransactionCount() == null ? 0L : row.getTransactionCount();
        return new CategoryTotals(row.getCategory(), amount, row.getCurrency(), count);
    }

    private SourceStatusResult toSourceStatus(SourceSyncStateEntity state, OffsetDateTime now) {
        OffsetDateTime lastSync = state.getLastSuccessfulSync();
        return new SourceStatusResult(
                state.getSourceId(),
                state.getName() != null ? state.getName() : state.getSourceId(),
                state.getStatus(),
                lastSync,
                state.getLastAttemptedSync(),
                state.getFailureCount(),
                lastSync == null ? null : Duration.between(lastSync, now).getSeconds(),
                freshnessStatus(lastSync, now));
    }

    private TransactionView toView(TransactionEntity entity) {
        return new TransactionView(
                entity.getId(),
                new MoneyValue(entity.getAmount(), entity.getCurrency()),
                entity.getDirection().name(),
                entity.getTransactionDate(),
                entity.getDescription(),
                entity.getMerchantName(),
                entity.getCategoryCode(),
                new SourceRef(entity.getSourceId(), entity.getSourceTransactionId()));
    }

    private MetaInfo buildMeta(String nextCursor, boolean hasMore) {
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, SourceSyncStateEntity> states = syncStateRepository.findAll().stream()
                .collect(Collectors.toMap(SourceSyncStateEntity::getSourceId, Function.identity(), (a, b) -> a));

        List<SourceFreshnessInfo> sources = new ArrayList<>(configuredSourceIds.size());
        boolean allSuccessful = true;
        for (String sourceId : configuredSourceIds) {
            SourceSyncStateEntity state = states.get(sourceId);
            boolean available = state != null && STATUS_SUCCESS.equals(state.getStatus());
            if (!available) {
                allSuccessful = false;
            }
            OffsetDateTime lastSync = state == null ? null : state.getLastSuccessfulSync();
            sources.add(new SourceFreshnessInfo(sourceId, lastSync, available ? AVAILABLE : UNAVAILABLE));
        }

        String overallStatus = sources.stream()
                .map(source -> freshnessStatus(source.lastSync(), now))
                .max(Comparator.comparingInt(FRESHNESS_SEVERITY::indexOf))
                .orElse(UNKNOWN);

        FreshnessInfo freshness = new FreshnessInfo(overallStatus, now, List.copyOf(sources));
        return new MetaInfo(nextCursor, hasMore, allSuccessful ? COMPLETE : PARTIAL, freshness);
    }

    private String freshnessStatus(OffsetDateTime lastSync, OffsetDateTime now) {
        if (lastSync == null) {
            return UNKNOWN;
        }
        long ageSeconds = Duration.between(lastSync, now).getSeconds();
        if (ageSeconds <= freshSeconds) {
            return FRESH;
        }
        if (ageSeconds <= staleSeconds) {
            return STALE;
        }
        return VERY_STALE;
    }

    private OffsetDateTime parseStart(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Invalid startDate: expected yyyy-MM-dd or ISO-8601 offset date-time");
        }
    }

    private OffsetDateTime parseEnd(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(value).atTime(LocalTime.MAX).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Invalid endDate: expected yyyy-MM-dd or ISO-8601 offset date-time");
        }
    }
}
