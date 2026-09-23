package za.co.evilcorp.transact.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class TenantContext {
    private static final ThreadLocal<UUID> currentCustomerId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentTenantId = new ThreadLocal<>();

    public void setTenant(UUID customerId, String tenantId) {
        currentCustomerId.set(customerId);
        currentTenantId.set(tenantId);
    }

    public Optional<UUID> getCustomerId() {
        return Optional.ofNullable(currentCustomerId.get());
    }

    public Optional<String> getTenantId() {
        return Optional.ofNullable(currentTenantId.get());
    }

    public void clear() {
        currentCustomerId.remove();
        currentTenantId.remove();
    }
}
