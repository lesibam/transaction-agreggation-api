package za.co.evilcorp.transact.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import za.co.evilcorp.transact.domain.model.AccountType;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.infrastructure.persistence.entity.AccountEntity;
import za.co.evilcorp.transact.infrastructure.persistence.entity.CustomerEntity;
import za.co.evilcorp.transact.infrastructure.persistence.entity.TransactionEntity;
import za.co.evilcorp.transact.infrastructure.persistence.repository.AccountRepository;
import za.co.evilcorp.transact.infrastructure.persistence.repository.CustomerRepository;
import za.co.evilcorp.transact.infrastructure.persistence.repository.TransactionRepository;
import za.co.evilcorp.transact.security.JwtTokenProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Shared base for full-stack tests: one Spring context (identical configuration
 * across subclasses), shared Testcontainers Postgres/Kafka, real embedded server
 * on a random port, JDK HttpClient helpers, JWT minting and seeding utilities.
 *
 * Seeded accounts always use a unique sourceProvider so they never collide with
 * the SOURCE_A/B/C accounts the persister resolves by source provider.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final UUID DEMO_CUSTOMER_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000001");

    @DynamicPropertySource
    static void sharedInfrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedContainers.postgres().getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedContainers.postgres().getUsername());
        registry.add("spring.datasource.password", () -> SharedContainers.postgres().getPassword());
        registry.add("spring.kafka.bootstrap-servers", () -> SharedContainers.kafka().getBootstrapServers());
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JwtTokenProvider tokenProvider;

    @Autowired
    protected CustomerRepository customerRepository;

    @Autowired
    protected AccountRepository accountRepository;

    @Autowired
    protected TransactionRepository transactionRepository;

    protected final HttpClient http = HttpClient.newHttpClient();
    protected final ObjectMapper json = new ObjectMapper();

    protected String mintToken(UUID customerId, String... roles) {
        return tokenProvider.createToken(customerId.toString(), "tenant-1", List.of(roles));
    }

    protected HttpResponse<String> get(String path) {
        return get(path, null, null);
    }

    protected HttpResponse<String> get(String path, String bearerToken) {
        return get(path, bearerToken, null);
    }

    protected HttpResponse<String> get(String path, String bearerToken, String correlationId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Accept", "application/json")
            .GET();
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            builder.header("X-Correlation-ID", correlationId);
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP GET failed for " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP GET interrupted for " + path, e);
        }
    }

    protected JsonNode jsonBody(HttpResponse<String> response) {
        try {
            return json.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Response body is not valid JSON: " + response.body(), e);
        }
    }

    protected record SeededCustomer(UUID customerId, UUID accountId) {
    }

    protected SeededCustomer seedCustomerWithAccount() {
        OffsetDateTime now = OffsetDateTime.now();
        String unique = UUID.randomUUID().toString();
        CustomerEntity customer = customerRepository.save(CustomerEntity.builder()
            .externalId("ext-" + unique)
            .name("Seed Customer")
            .email(unique + "@seed.transact.local")
            .tenantId("tenant-1")
            .createdAt(now)
            .updatedAt(now)
            .createdBy("TEST")
            .updatedBy("TEST")
            .build());
        AccountEntity account = accountRepository.save(AccountEntity.builder()
            .customerId(customer.getId())
            .accountType(AccountType.CHECKING)
            .currency("ZAR")
            .sourceProvider("SEED-" + unique)
            .createdAt(now)
            .updatedAt(now)
            .createdBy("TEST")
            .updatedBy("TEST")
            .build());
        return new SeededCustomer(customer.getId(), account.getId());
    }

    protected TransactionEntity seedTransaction(
            UUID customerId,
            UUID accountId,
            String sourceTransactionId,
            TransactionDirection direction,
            BigDecimal amount,
            String currency,
            String categoryCode,
            OffsetDateTime transactionDate) {
        OffsetDateTime now = OffsetDateTime.now();
        return transactionRepository.save(TransactionEntity.builder()
            .customerId(customerId)
            .accountId(accountId)
            .normalizationVersion("1")
            .amount(amount)
            .currency(currency)
            .direction(direction)
            .transactionDate(transactionDate)
            .categoryCode(categoryCode)
            .sourceId("SEED-SOURCE")
            .sourceTransactionId(sourceTransactionId)
            .ingestedAt(now)
            .createdAt(now)
            .updatedAt(now)
            .createdBy("TEST")
            .updatedBy("TEST")
            .build());
    }
}
