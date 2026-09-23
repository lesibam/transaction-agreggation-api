package za.co.evilcorp.transact.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "source_sync_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceSyncStateEntity {
    @Id
    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "last_successful_sync")
    private OffsetDateTime lastSuccessfulSync;

    @Column(name = "last_attempted_sync")
    private OffsetDateTime lastAttemptedSync;

    @Column
    private String cursor;

    @Column(nullable = false)
    private String status;

    @Column(name = "failure_count")
    private Integer failureCount;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(length = 255)
    private String name;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;
}
