package za.co.evilcorp.transact.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerAccessValidatorTest {

    private final CustomerAccessValidator validator = new CustomerAccessValidator();
    private final UUID pathCustomerId = UUID.randomUUID();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(UUID principalCustomerId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                principalCustomerId.toString(),
                "n/a",
                List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    void matchingPrincipalIsAllowed() {
        authenticateAs(pathCustomerId, "ROLE_CUSTOMER");

        assertThatCode(() -> validator.requireAccess(pathCustomerId))
            .doesNotThrowAnyException();
    }

    @Test
    void mismatchedPrincipalIsDenied() {
        authenticateAs(UUID.randomUUID(), "ROLE_CUSTOMER");

        assertThatThrownBy(() -> validator.requireAccess(pathCustomerId))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("does not match");
    }

    @Test
    void adminRoleBypassesCustomerOwnershipCheck() {
        authenticateAs(UUID.randomUUID(), "ROLE_ADMIN");

        assertThatCode(() -> validator.requireAccess(pathCustomerId))
            .doesNotThrowAnyException();
    }

    @Test
    void adminStillBypassesWhenPathCustomerIdIsNullCheckIrrelevant() {
        authenticateAs(UUID.randomUUID(), "ROLE_ADMIN");

        assertThatCode(() -> validator.requireAccess(UUID.randomUUID()))
            .doesNotThrowAnyException();
    }

    @Test
    void unparseablePrincipalNameIsDenied() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "not-a-uuid",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));

        assertThatThrownBy(() -> validator.requireAccess(pathCustomerId))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anonymousAuthenticationIsDenied() {
        SecurityContextHolder.getContext().setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(() -> validator.requireAccess(pathCustomerId))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void missingAuthenticationIsDenied() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> validator.requireAccess(pathCustomerId))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void nullPathCustomerIdIsDeniedEvenForMatchingPrincipal() {
        authenticateAs(pathCustomerId, "ROLE_CUSTOMER");

        assertThatThrownBy(() -> validator.requireAccess(null))
            .isInstanceOf(AccessDeniedException.class);
    }
}
