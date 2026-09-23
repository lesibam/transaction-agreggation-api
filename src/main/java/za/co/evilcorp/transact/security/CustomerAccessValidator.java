package za.co.evilcorp.transact.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CustomerAccessValidator {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    public void requireAccess(UUID pathCustomerId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("Access denied: no authenticated customer");
        }
        if (authentication.getAuthorities().stream()
                .anyMatch(authority -> ROLE_ADMIN.equals(authority.getAuthority()))) {
            return;
        }
        UUID principalCustomerId = parsePrincipal(authentication.getName());
        if (principalCustomerId == null || pathCustomerId == null || !pathCustomerId.equals(principalCustomerId)) {
            throw new AccessDeniedException("Access denied: customer does not match authenticated principal");
        }
    }

    private UUID parsePrincipal(String principal) {
        if (principal == null || principal.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
