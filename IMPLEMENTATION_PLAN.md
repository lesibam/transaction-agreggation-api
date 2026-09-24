# Implementation Plan: Transact - Transaction Aggregation API

This document serves as the master implementation roadmap for the **Transact** platform. It translates the high-level architectural goals of the Staff Engineer Guide into a concrete, phased execution plan, utilizing the specialized agent team defined in `claude.md`.

> **Checkbox policy:** `[x]` marks only work that is verifiably delivered in this repository. `[ ]` marks work that is partial, untested, or not started — the task text is retained as the target, not as a claim of completion.

## 1. Project Overview

**Objective**: Build a production-grade system to aggregate, normalize, and categorize financial transactions from heterogeneous sources, exposing them via a query-oriented API.

**Core Tech Stack**:
- **Build Tool**: Maven 3.9+
- **Runtime**: Java 21 / Spring Boot 4.1.1
- **Persistence**: PostgreSQL 16 / Flyway
- **Messaging**: Spring Kafka (Abstracted via the application-owned `TransactionEventPublisher` port)
- **Observability**: Micrometer / Prometheus endpoint / Grafana (default containers)
- **Security**: Spring Security / JWT / Multi-tenant isolation
- **Infrastructure**: Docker / Docker Compose / Helm / Kubernetes
- **Testing**: JUnit 5 / Testcontainers / Playwright

---

## 2. Foundational Principles

Every task in this plan must adhere to these "Staff Engineer" constraints:
- **Monetary Correctness**: Use `BigDecimal` or minor-unit integers. Never use floating point.
- **Data Provenance**: Every record must track its source, source ID, and ingestion timestamp.
- **Idempotency**: Ingestion must be safe to retry without duplicating data (Database uniqueness constraints).
- **Eventual Consistency**: The API must expose freshness metadata; partial results must never be presented as complete.
- **Resilience**: Outbound work must have explicit timeouts and bounded backoff. Circuit breakers/bulkheads are **deferred evolution** (see README Future Evolution) — do not claim them.
- **Zero Trust**: No trust in frontend authorization; every request must be validated for tenant/customer ownership.

---

## 3. Agent Responsibility Matrix

| Phase | Lead Agent | Supporting Agents | Primary Ownership |
| :--- | :--- | :--- | :--- |
| **0: Design** | Architect | Domain, Doc, Coord | `docs/` |
| **1: Persistence** | DBA | Domain, Backend | `src/main/resources/db/migration/` |
| **2: Ingestion** | Integration | Domain, Backend | `infrastructure/integration/` |
| **3: Categorization** | Domain | Backend, Integration | `domain/` |
| **4: API** | Backend | Doc, Domain | `api/`, `application/` |
| **5: Security** | Security | Backend, DBA | `security/` |
| **6: SRE** | SRE | Architect, Integration | `Dockerfile`, `docker-compose.yml` |
| **7: QA** | Testing | All | `src/test/`, `tests/e2e/` |
| **8: Deploy** | SRE | Architect | `helm/`, `.github/workflows/` |
| **Ongoing: Retrospective** | Meta-Agent | Coordinator | `claude.md` (proposals only), `docs/retrospectives/` |

The Meta-Agent's row is not phase-bound: it runs after every phase reaches "Done" (§6) and after any incident, feeding evidence-backed proposals back into this document and `claude.md` rather than owning implementation code.

---

## 4. Phased Implementation Roadmap

### Phase 0: Foundations & Contracts (The "Design-First" Phase)
*Goal: Eliminate ambiguity before a single line of production code is written.*

- [x] **Artifact 01: Assumptions**: Document all business and technical assumptions (e.g., source data formats, expected volumes).
- [x] **Artifact 02: Domain Model**: Define the Canonical Transaction Model, Identity strategy (`source_id`, `source_transaction_id`), and Direction (`DEBIT`/`CREDIT`).
- [x] **Artifact 03: Architecture**: Define the Modular Monolith boundaries and the messaging port (`TransactionEventPublisher`) interface.
- [x] **Artifact 04: API Contract**: Create a detailed OpenAPI spec including pagination, filtering, and freshness metadata in responses.
- [x] **Artifact 05: ADRs**: Document key decisions (e.g., why PostgreSQL, why eventual consistency).
- [x] **Project Scaffolding**: Initialize Spring Boot 4 project with package `za.co.evilcorp.transact`.

### Phase 1: Core Domain & Persistence
*Goal: Establish the source of truth and data invariants.*

- [x] **Schema Design**: Implement UUID primary keys and the required audit fields (`created_at`, `updated_at`, etc.).
- [x] **Invariant Enforcement**: Create Flyway migrations for `UNIQUE(source_id, source_transaction_id)` and non-null constraints.
- [x] **Domain Entities**: Implement the Canonical Transaction and Account entities in `za.co.evilcorp.transact.domain`.
- [x] **Repository Layer**: Implement Spring Data JPA repositories with explicit joins to avoid N+1 queries.

### Phase 2: Ingestion & Normalization
*Goal: Reliable, idempotent data movement from sources to the store.*

- [x] **Source Adapter Interface**: Implement the `TransactionSource` port to decouple domain from mock JSON formats.
- [x] **Source Transports (registry-driven)**: Adapters are registry-driven transports selected by `app.sources.registry` (ADR-011) — `MOCK`, `KAFKA`, `S3`, and `HTTP`; KAFKA/S3/HTTP are implemented with unit + integration tests via Testcontainers, while MFT and other future transports are future evolution (see README Future Evolution). Normalization strategies are code-owned and keyed by config: `SourceNormalizer` classes in `infrastructure/integration/normalizer`, selected by the registry's `normalizer` field — normalization still happens at the adapter boundary (ADR-006). *(Supersedes the original "Mock Adapters for Source A, B, and C with normalization inside the adapter" bullet — the boundary principle stands; registration moved from code to config.)*
- [x] **Ingestion Pipeline**: Implement the scheduler (ShedLock-guarded) and the flow: fetch → normalize → publish (`TransactionIngestedEvent`) → consume → persist.
- [x] **Incremental Sync**: Implement `SourceSyncState` to track cursors and avoid full history re-ingestion.
- [x] **Idempotency Handling**: Implement insert-or-ignore semantics via the DB unique constraint (no check-then-insert races); sync state records `SUCCESS`/`FAILED`.

### Phase 3: Categorization Engine
*Goal: Transform raw transactions into categorized financial insights.*

- [x] **Categorization Abstraction**: Implement `TransactionCategorizer` interface.
- [x] **Deterministic Rule Engine**: Build a rule-based categorizer (Merchant match → Category; ordered, first-match-wins).
- [x] **Versioning System**: Implement category versioning (`category_version`) to track how a transaction was categorized over time.
- [x] **Explainability**: Store the specific rule ID (`rule_id`) that triggered the categorization for auditability.

### Phase 4: The Query API
*Goal: High-performance, consumer-centric data exposure.*

- [x] **Query Services**: Implement application services for filtered transaction retrieval.
- [x] **Cursor Pagination**: Implement stable keyset pagination (`transaction_date DESC, id DESC`).
- [x] **Freshness Metadata**: Implement logic to calculate `current_time - last_successful_sync` and include it in API responses (thresholds configurable; 5/30-minute defaults).
- [x] **Aggregation Logic**: Build summaries (SQL `SUM` per currency, category breakdown) ensuring no silent currency conversion.

### Phase 5: Security & Tenancy
*Goal: Hardening the system for multi-tenant production use.*

- [x] **Authentication**: Implement JWT-based authentication with Spring Security.
- [x] **Tenant Isolation**: Implement row-level ownership validation (`CustomerAccessValidator`: JWT `sub` must match path `customerId` unless ADMIN).
- [x] **RBAC/ABAC**: Define roles (`ROLE_CUSTOMER`, `ROLE_ADMIN`) and map them to endpoint access in `SecurityConfig` (`/v1/customers/**` → CUSTOMER or ADMIN; `/v1/admin/**` → ADMIN only). *(No fine-grained `transactions:read`-style permission model exists — role checks only.)*
- [x] **Secret Management**: Configure environment-based secret injection (JWT secret via `app.security.jwt-secret` env var; no hard-coded passwords).

### Phase 6: Production Observability & SRE
*Goal: Transform observability from "logs and metrics" to "actionable production intelligence".*

- [ ] **SLI/SLO Definition**: Define and implement Service Level Indicators for:
    - **Availability**: Error rate < 0.1% for critical endpoints.
    - **Latency**: p99 response time < 200ms for transaction queries.
    - **Freshness**: 95% of sources synced within last 15 minutes.
- [x] **Advanced Metric Instrumentation** *(delivered scope: Micrometer ingestion sync timer + record counters exposed at `/actuator/prometheus`; per-endpoint error counters, DB-query timers, and Kafka consumer-lag metrics NOT yet instrumented)*:
    - **Error Rates**: Per-endpoint and per-source error counters (4xx vs 5xx).
    - **Performance**: Timers for DB query execution and external API call latency.
    - **Messaging**: Detailed Kafka consumer lag (offsets) and processing time per record.
- [ ] **Distributed Tracing**: Implement OpenTelemetry trace propagation across the ingestion pipeline and API.
- [x] **Structured Logging**: JSON logback output (`LogstashEncoder`) in the `prod` profile or when no profile is active, with Correlation IDs (`X-Correlation-ID` → MDC `correlationId`) for request-flow reconstruction. *(Span IDs require the OpenTelemetry item above — not delivered.)*
- [x] **Intelligent Health Checks**: Actuator health indicator that distinguishes "process alive" from "functional capability" — per-source `SUCCESS`/`FAILED` sync detail on `/actuator/health`.
- [ ] **Operational Dashboards**:
    - **Executive View**: High-level SLI status and system health.
    - **Ingestion View**: Source freshness, sync failure rates, and lag.
    - **Infra View**: JVM memory, DB connection pool saturation, and CPU usage.
- [ ] **Alerting Strategy**: Configure alerts for SLI breaches, high error spikes, and critical consumer lag thresholds.

### Phase 7: Quality Assurance & Performance
*Goal: Proving the system meets the "Staff Engineer" bar.*

- [x] **Integration Suite**: Implement Testcontainers-based tests for the ingestion pipeline and persistence invariants (`TransactionRepositoryTest`, `ResilienceTest`, `IngestionServiceTest`).
- [x] **E2E Workflows**: Use Playwright to verify the full flow from "Mock Source Data" → "API Response" (`tests/e2e/transaction_flow.spec.ts`). *(Specs cover list/summary/freshness/isolation/401/admin-RBAC against a running stack. `scripts/mint-jwt.mjs` + `tests/e2e/global-setup.ts` mint a real JWT; the suite skips cleanly when the app is not up — treat "skipped" as NOT RUN. Playwright is not part of the Maven build. Verified 2026-09-23: 5/5 locally against `docker-compose.e2e.yml` AND 5/5 in CI via the `e2e-smoke` job with zero-skipped enforced.)*
- [x] **Load Testing**: Run K6 scripts to identify bottlenecks in the query API and database. *(Delivered: `scripts/load-test.js` — smoke / ramped-load / soak scenarios against the SLI endpoints (keyset list incl. cursor hop + filtered query, per-currency summary), in-script HS* JWT minting mirroring `scripts/mint-jwt.mjs`, thresholds wired to the SLI targets (error rate < 0.1%, p99 < 200 ms per endpoint). Verified locally 2026-09-24 against the compose stack: load profile 20 VUs (~20 rps, 5,467 reqs, 0 failures, p99 ≤ 24 ms on both SLI endpoints), soak 8 VUs / 5 min (3,353 reqs, 0 failures, p95 ≤ 21 ms). Deliberately NOT run in per-PR CI — shared-runner timings are noise; re-run against a production-like environment before making capacity claims; local numbers are a baseline, not proof.)*
- [ ] **Failure Injection**: Simulate source outages and Kafka crashes to verify resilience (Partial Results logic). *(Partial-result metadata is asserted in e2e; systematic fault-injection suite not built.)*

### Phase 8: Deployment & CI/CD
*Goal: Immutable, reproducible shipping.*

- [x] **Containerization**: Build a multi-stage, non-root Dockerfile for the application.
- [x] **Orchestration**: Create Helm charts for Kubernetes deployment.
- [x] **CI Pipeline**: Automate: `Compile → Test → Scan → Publish → Deploy`. *(Verified green on remote runs 2026-09-23 (`github.com/lesibam/transaction-agreggation-api`): build + 90-test Testcontainers suite → containerize with a **blocking** Trivy gate (CRITICAL/HIGH, `ignore-unfixed`; dated exceptions in `.trivyignore`) — the gate caught and drove the fix of three CRITICAL embedded-Tomcat CVEs (`4807e23`) → `e2e-smoke`: compose stack on the runner, health/auth/RFC-7807 contract assertions, Playwright with **zero-skipped enforcement** (`scripts/e2e-assert.mjs`) → publish/deploy steps conditional on Docker Hub / KUBECONFIG secrets by design (forks don't fail). `main` is protected: required checks, linear history, no force pushes/deletions.)*
- [ ] **Recovery Plan**: Document and test the database restore process (RPO/RTO verification). *(Plan documented in `docs/recovery-plan.md` as UNTESTED targets — no restore drill executed, so this stays open.)*

---

## 5. Cross-Cutting Concern Checklist

- [ ] **Observability**: High error rates, slow p99s, and consumer lag are explicitly alertable. *(Metrics exposed; no alert rules configured.)*
- [x] **Tracing**: Every request is traceable from API entry via correlation ID (`X-Correlation-ID` → MDC → ProblemDetail `traceId`). *(Distributed trace propagation across the pipeline is not delivered — see Phase 6.)*
- [x] **BigDecimal**: No `double` or `float` for money.
- [x] **No-N+1**: List endpoints use single flat-entity queries with keyset pagination; summaries aggregate in SQL.
- [x] **Timeouts**: Outbound fetch/publish loop uses explicit timeouts with bounded backoff. *(No outbound HTTP clients exist yet — circuit breakers/bulkheads remain deferred.)*
- [x] **Audit Trail**: All business records have `created_by` and `updated_by`.
- [x] **Statelessness**: The API layer holds no local state, allowing horizontal scaling (ShedLock guards the scheduler across instances).

## 6. Definition of Done (DoD)

A phase is considered "Done" when:
1. All tasks in the phase are implemented.
2. Code is reviewed by at least one supporting agent from `claude.md`.
3. Integration tests pass in a Testcontainers environment.
4. OpenAPI spec is updated and reflects the actual implementation.
5. The Coordinator has signed off on the architectural consistency.
6. The Meta-Agent has run a retrospective, logged any recurring findings in `docs/retrospectives/LESSONS.md`, and proposed any resulting updates to `claude.md`.
