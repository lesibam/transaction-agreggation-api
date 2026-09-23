# Artifact 01: Assumptions - Transact API

This document captures the technical and business assumptions made for the Transaction Aggregation API. These assumptions form the basis of the architectural decisions and must be validated by stakeholders.

## 1. Data Source Assumptions
- **Sources are in-process mocks**: The three sources (`SOURCE_A`, `SOURCE_B`, `SOURCE_C`) are served by in-process mock adapters, not production HTTP endpoints. There are no outbound HTTP clients; timeouts and bounded backoff apply to the fetch/publish loop, and circuit breakers are not implemented (they are evolution, not an assumption of current behaviour).
- **Source Heterogeneity**: It is assumed that the three mock data sources provide data in different formats (JSON, XML, or different JSON schemas) and use different naming conventions for the same concepts (e.g., `amount` vs `value`). Normalization happens inside each adapter.
- **Identity**: We assume that source-provided transaction IDs are unique *only within that specific source*. A global identity is required as `(source_id, source_transaction_id)`.
- **Availability**: Sources may be unavailable or slow. The system must handle these failures gracefully without blocking the entire API response; failed sources yield `completeness = PARTIAL`, never a total API failure.
- **Volume**: The system is designed for a "Staff Engineer assessment" scale but must demonstrate a path to handle millions of transactions (hence the use of PostgreSQL indexing and keyset/cursor pagination).
- **Consistency**: We assume the upstream sources do not provide a global snapshot; therefore, the system will operate on an **eventual consistency** model with freshness metadata.

## 2. Domain Assumptions
- **Currency**: We assume transactions may arrive in different currencies. The system will store them as-is and will **not** perform automatic currency conversion unless explicitly requested by a future requirement. Aggregates are always reported per currency.
- **Monetary Value**: All financial calculations must be performed using `BigDecimal` (`DECIMAL(19, 4)` in PostgreSQL) to avoid precision loss.
- **Categorization**: Initial categorization is based on deterministic merchant-name matching with a persisted `ruleId` and `category_version`. We assume this logic can be updated independently of the ingestion pipeline.
- **Deduplication**: Cross-source deduplication is *not* performed unless a reliable correlation key is identified. We assume that the same transaction appearing in two different sources with different IDs should be treated as two separate transactions to avoid "inventing certainty."
- **Seed Data**: A demo customer `00000000-0000-0000-0000-000000000001` with three accounts (one per source) exists for local verification.

## 3. Technical Assumptions
- **Runtime**: The application will run on Java 21 with Spring Boot 4.1.1, packaged as a modular monolith.
- **Infrastructure**: The system will be deployed as a modular monolith on Kubernetes (Helm chart provided), allowing independent scaling of the whole service; multi-instance safety for the ingestion scheduler is provided by **ShedLock** so only one instance runs a given sync cycle.
- **Persistence**: PostgreSQL 16 is the primary store. All schema changes must be managed via immutable Flyway migrations (V1 + V2).
- **Messaging**: Spring Kafka is present and used, but only behind the application-owned `TransactionEventPublisher` port (`KafkaTransactionEventPublisher` is the current implementation). Topics: `transactions.ingested` (main) and `transactions.dlq` (quarantine). Delivery is at-least-once with an idempotent consumer enforced by `UNIQUE(source_id, source_transaction_id)`.
- **Observability**: The system is monitored via Micrometer metrics exposed on the Actuator Prometheus endpoint (`/actuator/prometheus`), with correlation IDs (`X-Correlation-ID`) in logs and JSON logback output (`LogstashEncoder`) in the `prod` profile or when no profile is active. SLI/SLO dashboards and alerting are not assumed to exist yet.
- **Security**: We assume a multi-tenant environment where a `customerId` must be validated against the authenticated JWT token (`sub`) for every request — unless the caller holds the `ADMIN` role — and the JWT signing secret is injected via environment variable (`app.security.jwt-secret`), never hard-coded.
- **Disaster Recovery**: RPO/RTO figures in `recovery-plan.md` are **targets only**; no restore drill has been executed, so no recovery capability is assumed to be proven.

---
**Status**: Implemented
**Owner**: Architect
**Date**: 2026-09-23
