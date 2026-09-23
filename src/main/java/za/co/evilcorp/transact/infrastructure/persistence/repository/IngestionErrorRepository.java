package za.co.evilcorp.transact.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import za.co.evilcorp.transact.infrastructure.persistence.entity.IngestionErrorEntity;
import java.util.UUID;

@Repository
public interface IngestionErrorRepository extends JpaRepository<IngestionErrorEntity, UUID> {
}
