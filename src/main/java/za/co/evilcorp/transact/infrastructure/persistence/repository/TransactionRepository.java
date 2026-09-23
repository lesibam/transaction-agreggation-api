package za.co.evilcorp.transact.infrastructure.persistence.repository;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import za.co.evilcorp.transact.application.dto.CategorySummaryRow;
import za.co.evilcorp.transact.application.dto.CurrencySummaryRow;
import za.co.evilcorp.transact.infrastructure.persistence.entity.TransactionEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    default List<TransactionEntity> searchByCustomerKeyset(
            UUID customerId,
            String category,
            String direction,
            OffsetDateTime startDate,
            OffsetDateTime endDate,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            OffsetDateTime beforeDate,
            UUID beforeId,
            int limit) {
        return searchByCustomerKeysetPageable(
            customerId,
            category,
            direction,
            startDate,
            endDate,
            minAmount,
            maxAmount,
            beforeDate,
            beforeId,
            PageRequest.of(0, Math.max(limit, 1)));
    }

    @Query("""
        SELECT t FROM TransactionEntity t
        WHERE t.customerId = :customerId
          AND (
                CAST(:beforeDate AS offsetdatetime) IS NULL
                OR t.transactionDate < :beforeDate
                OR (t.transactionDate = :beforeDate AND t.id < :beforeId)
              )
          AND (CAST(:category AS string) IS NULL OR t.categoryCode = :category)
          AND (CAST(:direction AS string) IS NULL OR CAST(t.direction AS string) = :direction)
          AND (CAST(:startDate AS offsetdatetime) IS NULL OR t.transactionDate >= :startDate)
          AND (CAST(:endDate AS offsetdatetime) IS NULL OR t.transactionDate <= :endDate)
          AND (CAST(:minAmount AS bigdecimal) IS NULL OR t.amount >= :minAmount)
          AND (CAST(:maxAmount AS bigdecimal) IS NULL OR t.amount <= :maxAmount)
        ORDER BY t.transactionDate DESC, t.id DESC
        """)
    List<TransactionEntity> searchByCustomerKeysetPageable(
        @Param("customerId") UUID customerId,
        @Param("category") String category,
        @Param("direction") String direction,
        @Param("startDate") OffsetDateTime startDate,
        @Param("endDate") OffsetDateTime endDate,
        @Param("minAmount") BigDecimal minAmount,
        @Param("maxAmount") BigDecimal maxAmount,
        @Param("beforeDate") OffsetDateTime beforeDate,
        @Param("beforeId") UUID beforeId,
        Pageable pageable);

    @Query(value = """
        SELECT
            currency AS "currency",
            SUM(CASE WHEN direction = 'DEBIT' THEN total_amount ELSE 0 END) AS "totalDebit",
            SUM(CASE WHEN direction = 'CREDIT' THEN total_amount ELSE 0 END) AS "totalCredit",
            SUM(CASE WHEN direction = 'DEBIT' THEN txn_count ELSE 0 END) AS "debitCount",
            SUM(CASE WHEN direction = 'CREDIT' THEN txn_count ELSE 0 END) AS "creditCount"
        FROM (
            SELECT
                currency,
                direction,
                SUM(amount) AS total_amount,
                COUNT(*) AS txn_count
            FROM transactions
            WHERE customer_id = :customerId
              AND (CAST(:startDate AS timestamp with time zone) IS NULL OR transaction_date >= :startDate)
              AND (CAST(:endDate AS timestamp with time zone) IS NULL OR transaction_date <= :endDate)
            GROUP BY currency, direction
        ) grouped
        GROUP BY currency
        """, nativeQuery = true)
    List<CurrencySummaryRow> summarizeByCurrency(
        @Param("customerId") UUID customerId,
        @Param("startDate") OffsetDateTime startDate,
        @Param("endDate") OffsetDateTime endDate);

    @Query(value = """
        SELECT
            COALESCE(category_code, 'UNCATEGORIZED') AS "category",
            currency AS "currency",
            SUM(amount) AS "amount",
            COUNT(*) AS "transactionCount"
        FROM transactions
        WHERE customer_id = :customerId
          AND (CAST(:startDate AS timestamp with time zone) IS NULL OR transaction_date >= :startDate)
          AND (CAST(:endDate AS timestamp with time zone) IS NULL OR transaction_date <= :endDate)
        GROUP BY currency, COALESCE(category_code, 'UNCATEGORIZED')
        """, nativeQuery = true)
    List<CategorySummaryRow> summarizeByCategory(
        @Param("customerId") UUID customerId,
        @Param("startDate") OffsetDateTime startDate,
        @Param("endDate") OffsetDateTime endDate);
}
