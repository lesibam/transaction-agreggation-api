package za.co.evilcorp.transact.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import za.co.evilcorp.transact.domain.model.TransactionDirection;

@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "idx_unique_source_transaction", columnList = "source_id, source_transaction_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "rule_id", length = 100)
    private String ruleId;

    @Column(name = "normalization_version", nullable = false, length = 20)
    private String normalizationVersion;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionDirection direction;

    @Column(name = "transaction_date", nullable = false)
    private OffsetDateTime transactionDate;

    @Column(name = "posted_at")
    private OffsetDateTime postedAt;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "merchant_name")
    private String merchantName;

    @Column(name = "category_code")
    private String categoryCode;

    @Column(name = "category_version")
    private Integer categoryVersion;

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Column(name = "source_transaction_id", nullable = false)
    private String sourceTransactionId;

    @Column(name = "ingested_at", nullable = false)
    private OffsetDateTime ingestedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;
}
