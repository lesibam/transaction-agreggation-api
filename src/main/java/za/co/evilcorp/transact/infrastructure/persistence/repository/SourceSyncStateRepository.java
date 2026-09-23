package za.co.evilcorp.transact.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import za.co.evilcorp.transact.infrastructure.persistence.entity.SourceSyncStateEntity;

@Repository
public interface SourceSyncStateRepository extends JpaRepository<SourceSyncStateEntity, String> {
}
