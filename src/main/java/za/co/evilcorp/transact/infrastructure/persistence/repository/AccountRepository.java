package za.co.evilcorp.transact.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import za.co.evilcorp.transact.infrastructure.persistence.entity.AccountEntity;
import java.util.List;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {
    List<AccountEntity> findByCustomerId(UUID customerId);

    List<AccountEntity> findAllBySourceProvider(String sourceProvider);
}
