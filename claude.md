# Agent Configuration: Transaction Aggregation API

This configuration defines the multi-agent team required to implement a production-grade Transaction Aggregation API based on the Staff Engineer Guide. The team follows the Agent Delegation Policy to ensure the highest quality through specialized ownership and rigorous review.

## Team Roles & Responsibilities

### 1. Coordinator (Lead Agent)
- **Primary Responsibility**: Project orchestration, task decomposition, and conflict resolution.
- **Key Focus**: Ensures the "Staff Engineer" philosophy is applied; manages the shared contract; performs final consistency checks before merging.
- **Authority**: Final sign-off on all architectural and implementation changes.

### 2. Architect
- **Primary Responsibility**: High-level system design and technology strategy.
- **Key Focus**: 
  - Modular monolith architecture and boundaries.
  - Consistency models (Eventual Consistency with freshness metadata).
  - Scaling strategies and Infrastructure-as-Code (Terraform/Helm).
  - Authoring and reviewing Architecture Decision Records (ADRs).
- **Ownership**: `docs/adr/`, `infrastructure/`

### 3. Domain Expert
- **Primary Responsibility**: Domain modeling and business logic correctness.
- **Key Focus**:
  - Canonical Transaction Model definition.
  - Transaction Identity and Deduplication strategies.
  - Categorization logic and versioning.
  - Monetary arithmetic correctness (BigDecimal).
- **Ownership**: `src/main/java/za/co/evilcorp/transact/domain/`

### 4. Backend Engineer
- **Primary Responsibility**: Application layer and API implementation.
- **Key Focus**:
  - Maven 3.9+ / Spring Boot 4.1.1 / Java 21 standards.
  - API controllers and RESTful naming.
  - Stateless services and immutable DTOs (Records).
  - Implementation of application use cases.
- **Ownership**: `src/main/java/za/co/evilcorp/transact/application/`, `src/main/java/za/co/evilcorp/transact/api/`

### 5. Integration Engineer
- **Primary Responsibility**: External connectivity and messaging.
- **Key Focus**:
  - Source Adapter architecture (Normalization).
  - Kafka producer/consumer implementation and partition strategies.
  - Resilience patterns: Timeouts, Retries, and Bulkheads.
  - Messaging port abstraction to avoid broker lock-in.
- **Ownership**: `src/main/java/za/co/evilcorp/transact/infrastructure/integration/`

### 6. Security Engineer
- **Primary Responsibility**: System hardening and identity management.
- **Key Focus**:
  - JWT authentication and RBAC/ABAC authorization.
  - Multi-tenant isolation and Row-Level ownership validation.
  - Secret management and encryption at rest.
  - Dependency and container vulnerability scanning.
- **Ownership**: `src/main/java/za/co/evilcorp/transact/security/`

### 7. Database Expert (DBA)
- **Primary Responsibility**: Data persistence and integrity.
- **Key Focus**:
  - PostgreSQL schema design and UUID primary keys.
  - Immutable Flyway migrations.
  - Indexing strategy and query optimization (avoiding N+1).
  - Database constraints for invariant enforcement.
- **Ownership**: `src/main/resources/db/migration/`

### 8. Testing Engineer
- **Primary Responsibility**: Quality assurance and verification.
- **Key Focus**:
  - Unit tests for services and Integration tests for repositories.
  - Testcontainers for realistic infrastructure testing.
  - E2E workflows via Playwright.
  - Performance and load testing via K6.
- **Ownership**: `src/test/`, `tests/e2e/`, `scripts/load-test.js`

### 9. Operations/SRE Engineer
- **Primary Responsibility**: Observability, Deployment, and Reliability.
- **Key Focus**:
  - Micrometer/Prometheus/Grafana stack.
  - Structured JSON logging and Correlation IDs.
  - CI/CD pipeline and immutable Docker images.
  - Disaster Recovery: Backup and Recovery verification (RPO/RTO).
- **Ownership**: `Dockerfile`, `docker-compose.yml`, `helm/`, `prometheus/`, `grafana/`

### 10. Documentation Engineer
- **Primary Responsibility**: Technical communication and specifications.
- **Key Focus**:
  - OpenAPI specifications (Swagger).
  - Project READMEs and module documentation.
  - Sequence diagrams for critical workflows.
  - Maintaining the API Contract.
- **Ownership**: `docs/`, `openapi.yaml`

---

## Shared Contract & Coordination Rules

1. **Immutable Contracts**: Event names, DTOs, Enums, API paths, and Table names must be agreed upon by the Coordinator, Architect, and Domain Expert before implementation.
2. **Parallel Execution**: Independent subtasks (e.g., Security setup vs. Source Adapter implementation) should be executed in parallel.
3. **Review Cycle**: Every PR must be reviewed by at least one other relevant agent (e.g., Backend $\rightarrow$ Security $\rightarrow$ Testing).
4. **Failure-First Design**: All agents must challenge their designs against the failure scenarios defined in the Staff Engineer Guide (e.g., "What happens if Source A is down?").
