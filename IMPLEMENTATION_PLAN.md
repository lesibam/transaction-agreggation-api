# Implementation Plan: Transact - Transaction Aggregation API

This document serves as the master implementation roadmap for the **Transact** platform. It translates the high-level architectural goals of the Staff Engineer Guide into a concrete, phased execution plan, utilizing the specialized agent team defined in `claude.md`.

> **Checkbox policy:** `[x]` marks only work that is verifiably delivered in this repository. `[ ]` marks work that is partial, untested, or not started — the task text is retained as the target, not as a claim of completion.

## 1. Project Overview

**Objective**: Build a production-grade system to aggregate, normalize, and categorize financial transactions from heterogeneous sources, exposing them via a query-oriented API.

> **Scope note.** Phases 0–8 target the bar set by the Staff Engineer Guide, which explicitly discourages over-building infrastructure for an assessment exercise (see guide §85, "What Not to Build"). That is a different, lower bar than "safe to run in a real bank's production environment." **Phase 9** below is the gap between the two — see `docs/principal_engineer_review_report.md` for the review that identified it. Completing Phases 0–8 alone does not make this system bank production-ready.

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
| **9: Bank Production Readiness** | Architect | Security, SRE, DBA, Compliance & Governance, Data Reconciliation, Coordinator | `helm/`, `security/`, `docker-compose.yml`, `docs/recovery-plan.md`, `docs/compliance/` |
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
  Threshold-by-threshold feasibility (what's alertable today vs. blocked on new instrumentation) and the metrics/owner split with Operations/SRE Engineer are in `docs/observability-alerting-handoff.md`.
- [x] **Advanced Metric Instrumentation** *(delivered scope: Micrometer ingestion sync timer + record counters exposed at `/actuator/prometheus`; DB-query timers and Kafka consumer-lag metrics NOT yet instrumented. Correction, 2026-09-25: per-endpoint error counters are NOT missing the way this line previously implied — Boot's default `http_server_requests_seconds_count` already carries `uri` and `status` tags, so a per-endpoint error-rate alert needs no new counter, only an alert rule against a metric that already exists. See `docs/observability-alerting-handoff.md` §3.1.)*:
    - **Error Rates**: Per-endpoint and per-source error counters (4xx vs 5xx). *(Per-endpoint: already derivable from the default `http_server_requests_seconds_count`, see above. Per-source: covered separately by `transact.ingestion.source.sync.failure`, already emitted.)*
    - **Performance**: Timers for DB query execution and external API call latency. *(Still genuinely not instrumented — no per-query timers exist; out of scope for the observability-alerting handoff, which only covers metrics that already exist or are one small change away.)*
    - **Messaging**: Detailed Kafka consumer lag (offsets) and processing time per record. *(Still not instrumented — root cause and the one-method-call fix identified in `docs/observability-alerting-handoff.md` §3.5: `KafkaConsumerConfig` builds its `ConsumerFactory` manually and never registers a `MicrometerConsumerListener`.)*
- [ ] **Distributed Tracing**: Implement OpenTelemetry trace propagation across the ingestion pipeline and API. *(`docs/otel-tracing-handoff.md`: the ingestion pipeline — scheduler-triggered, crossing an async Kafka boundary — has zero correlation today, worse than the API side; Spring Kafka's built-in Observation support already solves the hard part of span propagation across that boundary, it just needs enabling on the hand-built `KafkaTemplate`/listener container factory. Needs a backend-choice ADR before any new container is added, per Architect's "What Not to Build" check.)*
- [x] **Structured Logging**: JSON logback output (`LogstashEncoder`) in the `prod` profile or when no profile is active, with Correlation IDs (`X-Correlation-ID` → MDC `correlationId`) for request-flow reconstruction. *(Span IDs require the OpenTelemetry item above — not delivered, but the JSON pipeline already emits every MDC key as a top-level field, so no logback change will be needed once `traceId`/`spanId` land in MDC — see `docs/otel-tracing-handoff.md` §2.)*
- [x] **Intelligent Health Checks**: `SyncHealthIndicator` distinguishes "process alive" from "functional capability" — overall `/actuator/health` `status` flips to `DOWN` if any source isn't `SUCCESS`. *(Correction, found 2026-09-25: per-source detail is NOT visible at `/actuator/health` itself — `management.endpoint.health.show-details` is left at the Boot default (`never`), so anonymous callers only ever see `{"status":...}`, matching the plain response captured in the C-01 incident postmortem. `GET /v1/admin/sources` (ADMIN token) is the actual way to see per-source `SUCCESS`/`FAILED` detail today; `./dev.sh status`/`seed` use it for exactly this reason.)*
- [ ] **Operational Dashboards**:
    - **Executive View**: High-level SLI status and system health.
    - **Ingestion View**: Source freshness, sync failure rates, and lag.
    - **Infra View**: JVM memory, DB connection pool saturation, and CPU usage.
  *(A first dashboard now auto-provisions with `./dev.sh start` — `grafana/dashboards/transact-overview.json`: ingestion outcomes, sync success/failure, HTTP latency/status, DB pool. It's an Ingestion-View-shaped start, not the Executive or Infra views above, and ships no alert rules.)*
- [ ] **Alerting Strategy**: Configure alerts for SLI breaches, high error spikes, and critical consumer lag thresholds. *(`docs/observability-alerting-handoff.md` has proposed alert rules/thresholds ready to implement today for 3 of 7 identified SLIs — API error rate, ingestion failure rate, DB pool saturation — with no new instrumentation required; still open pending SRE actually writing the rules.)*

### Phase 7: Quality Assurance & Performance
*Goal: Proving the system meets the "Staff Engineer" bar.*

- [x] **Integration Suite**: Implement Testcontainers-based tests for the ingestion pipeline and persistence invariants (`TransactionRepositoryTest`, `ResilienceTest`, `IngestionServiceTest`).
- [x] **E2E Workflows**: Use Playwright to verify the full flow from "Mock Source Data" → "API Response" (`tests/e2e/transaction_flow.spec.ts`). *(Specs cover list/summary/freshness/isolation/401/admin-RBAC against a running stack. `scripts/mint-jwt.mjs` + `tests/e2e/global-setup.ts` mint a real JWT; the suite skips cleanly when the app is not up — treat "skipped" as NOT RUN. Playwright is not part of the Maven build. Verified 2026-09-23: 5/5 locally against `docker-compose.e2e.yml` AND 5/5 in CI via the `e2e-smoke` job with zero-skipped enforced.)*
- [x] **Load Testing**: Run K6 scripts to identify bottlenecks in the query API and database. *(Delivered: `scripts/load-test.js` — smoke / ramped-load / soak scenarios against the SLI endpoints (keyset list incl. cursor hop + filtered query, per-currency summary), in-script HS* JWT minting mirroring `scripts/mint-jwt.mjs`, thresholds wired to the SLI targets (error rate < 0.1%, p99 < 200 ms per endpoint). Verified locally 2026-09-24 against the compose stack: load profile 20 VUs (~20 rps, 5,467 reqs, 0 failures, p99 ≤ 24 ms on both SLI endpoints), soak 8 VUs / 5 min (3,353 reqs, 0 failures, p95 ≤ 21 ms). Deliberately NOT run in per-PR CI — shared-runner timings are noise; re-run against a production-like environment before making capacity claims; local numbers are a baseline, not proof. `./dev.sh perf` also runs this script — see the note there for its env-var contract. `docs/load-testing-handoff.md` was originally written against an earlier, thinner version of this script, before this run — reconciled 2026-09-25 to describe the current script and this run, and to state precisely which of its two scoping gaps this run does and doesn't close (VUS-sweep gap partially closed; data-volume gap still open). Its reporting-template shape is still useful for the next run.)*
- [ ] **Failure Injection**: Simulate source outages and Kafka crashes to verify resilience (Partial Results logic). *(Partial-result metadata is asserted in e2e; systematic fault-injection suite not built.)*

### Phase 8: Deployment & CI/CD
*Goal: Immutable, reproducible shipping.*

- [x] **Containerization**: Build a multi-stage, non-root Dockerfile for the application.
- [x] **Orchestration**: Create Helm charts for Kubernetes deployment.
- [x] **CI Pipeline**: Automate: `Compile → Test → Scan → Publish → Deploy`. *(Verified green on remote runs 2026-09-23 (`github.com/lesibam/transaction-agreggation-api`): build + 90-test Testcontainers suite → containerize with a **blocking** Trivy gate (CRITICAL/HIGH, `ignore-unfixed`; dated exceptions in `.trivyignore`) — the gate caught and drove the fix of three CRITICAL embedded-Tomcat CVEs (`4807e23`) → `e2e-smoke`: compose stack on the runner, health/auth/RFC-7807 contract assertions, Playwright with **zero-skipped enforcement** (`scripts/e2e-assert.mjs`) → publish/deploy steps conditional on Docker Hub / KUBECONFIG secrets by design (forks don't fail). `main` is protected: required checks, linear history, no force pushes/deletions. **The "90-test" figure predates 2026-09-25's rebase onto `main`'s config-driven source registry (ADR-011), which added several new Testcontainers-backed test classes** (`KafkaTransactionSourceTest`, `S3TransactionSourceTest`, plus API-layer tests) **— the real current Testcontainers-suite count hasn't been re-verified in an environment with Docker since; this pipeline hasn't re-run since the rebase either.**)*
- [ ] **Recovery Plan**: Document and test the database restore process (RPO/RTO verification). *(Plan documented in `docs/recovery-plan.md` as UNTESTED targets — no restore drill executed, so this stays open. `docs/recovery-drill-handoff.md` now specifies exactly what "verify integrity" should mean when a drill runs — see below.)*

### Phase 9: Bank Production Readiness
*Goal: Close the gap between "meets the Staff Engineer Guide's bar" and "safe to carry a real bank's transaction data in production." None of this phase is required by the guide (§85 "What Not to Build" explicitly discourages this kind of infrastructure for an assessment exercise) — it exists because a regulated financial institution has different, stricter constraints than a take-home. Nothing in this phase is delivered today; every item below was identified in `docs/principal_engineer_review_report.md`'s bank-readiness assessment (2026-09-24).*

- [ ] **High-Availability Data Topology**: Replace the single-instance Postgres, Kafka, and Zookeeper in `docker-compose.yml` with a replicated topology (managed Postgres with standby/read replicas, Kafka with `replication.factor >= 3` spread across brokers/AZs, or managed equivalents) with tested automatic failover.
- [ ] **Multi-Replica, Zero-Downtime Deploys**: Raise `helm/transact/values.yaml`'s `replicaCount` (currently `1`) with a `PodDisruptionBudget`, `HorizontalPodAutoscaler`, pod anti-affinity across nodes/AZs, and a rolling-update or blue/green strategy, so a `helm upgrade` is never a visible outage.
- [ ] **Encryption in Transit**: TLS for every hop — client↔API, API↔Postgres (`sslmode=verify-full`), and API↔Kafka — replacing the `PLAINTEXT` listener map in `docker-compose.yml` and the unencrypted JDBC URL in `application.yml`; mTLS between internal services. *(A local-dev stepping stone exists: `./dev.sh certs && ./dev.sh start --tls` serves the API's own listener over self-signed HTTPS on `:8443` via `docker-compose.tls.yml`. It does not touch Postgres/Kafka traffic or add mTLS — this item stays open.)*
- [ ] **Encryption at Rest & Key Management**: Encrypted Postgres/Kafka volumes and backups; secrets sourced from a managed KMS/secrets manager instead of Helm's `.Values.secret.*` → rendered `Secret` pattern (principal engineer review, M-06), with rotation. *(2026-09-25: the chart now refuses to render with an empty/placeholder secret — `helm/transact/templates/secret.yaml` — but that's a fail-fast guard, not a KMS/ExternalSecrets migration; this item stays open.)*
- [ ] **Enterprise Identity & Access**: Replace the symmetric pre-shared-secret JWT (`JwtTokenProvider`) with asymmetric signing (RS256/ES256) validated against a JWKS endpoint from a real IdP (Okta/Entra/Keycloak), MFA enforced at the IdP, short-lived access tokens with refresh/revocation. *(M-03's `TenantContext` half is done — the class was deleted 2026-09-25, not wired in; see `docs/03-architecture.md` §2.5. The IdP/asymmetric-signing/MFA work below is still fully open.)*
- [ ] **Rate Limiting & Abuse Protection**: Per-client/per-token rate limiting (e.g., Resilience4j `RateLimiter` or an API gateway) and WAF/DDoS protection in front of the public endpoint — neither exists today.
- [ ] **Circuit Breakers & Bulkheads**: Deliver the Resilience4j work already flagged as deferred in `README.md` Future Evolution and §2 above — the `KAFKA`/`S3`/`HTTP` transports (`app.sources.registry`, ADR-011) already make real outbound calls with per-call timeouts and `IngestionService`-level retry/backoff, but nothing yet stops repeated hammering of a source that is down or bounds concurrent in-flight calls per source; required before any of those transports carries production traffic.
- [ ] **Source Reconciliation** *(owner: Data Reconciliation Engineer)*: A scheduled job that compares ingested record counts/sums against each source system's own totals for the same window and alerts on drift, so "the aggregation matches the source of truth" is a monitored fact, not an assumption. Not implemented, and not referenced anywhere outside one ADR's prose today. Alert thresholds and the metrics contract with Operations/SRE Engineer are pre-agreed in `docs/reconciliation-alerting-handoff.md`, so the job and its alerting can be built in parallel once someone picks this up.
- [ ] **Executed Disaster Recovery Drills** *(owner: DBA + Data Reconciliation Engineer define verification; Operations/SRE Engineer executes)*: Actually run the restore procedure in `docs/recovery-plan.md` against a real backup and measure RPO/RTO. Today the plan is explicitly an untested draft runbook, not evidence of recoverability. `docs/recovery-drill-handoff.md` names the seven schema constraints to re-verify, the untested Flyway-migration-replay scenario, and reconciliation as the strongest available restore evidence — closing the gap between "the app booted" and "the data is actually correct."
- [ ] **Governance & Change Control** *(owner: Compliance & Governance Agent)*: `CODEOWNERS` and mandatory dual review for changes under `domain/`, `security/`, and `db/migration/`; an audit trail for administrative actions (categorization rule changes, manual reprocessing) distinct from row-level `created_by`/`updated_by`; a documented change-advisory/release-approval process.
- [ ] **Data Classification & Retention** *(owner: Compliance & Governance Agent)*: A documented PII/data-classification policy and a retention/erasure procedure aligned to the applicable regime (POPIA, given `za.co.evilcorp`), reviewed before any `KAFKA`/`S3`/`HTTP` registry entry is pointed at a real upstream system carrying real customer data (today's registry only ever points at demo brokers/buckets/endpoints).
- [ ] **Independent Security Testing** *(owner: Compliance & Governance Agent, in partnership with Security)*: A penetration test and a SAST/dependency review beyond Trivy's image scan, completed before production go-live.

*Dependency note: this phase assumes Phase 6's and Phase 7's remaining open items (Distributed Tracing, Operational Dashboards, Alerting Strategy, Load Testing, Failure Injection) are also closed — none of the items above substitute for them. Four of those (Distributed Tracing, Alerting Strategy, Load Testing) already have a proposed handoff to Operations/SRE Engineer written; a fifth handoff covers Phase 8's Recovery Plan / this phase's Executed DR Drills — see `docs/handoffs-index.md` for all five.*

---

## 5. Cross-Cutting Concern Checklist

- [ ] **Observability**: High error rates, slow p99s, and consumer lag are explicitly alertable. *(Metrics exposed; no alert rules configured.)*
- [x] **Tracing**: Every request is traceable from API entry via correlation ID (`X-Correlation-ID` → MDC → ProblemDetail `traceId`). *(Distributed trace propagation across the pipeline is not delivered — see Phase 6.)*
- [x] **BigDecimal**: No `double` or `float` for money.
- [x] **No-N+1**: List endpoints use single flat-entity queries with keyset pagination; summaries aggregate in SQL.
- [x] **Timeouts**: Outbound fetch/publish loop uses explicit timeouts with bounded backoff. *(The `KAFKA`/`S3`/`HTTP` registry transports — ADR-011 — make real outbound calls today, each with its own per-transport timeout config; circuit breakers/bulkheads remain deferred, see Phase 9.)*
- [x] **Audit Trail**: All business records have `created_by` and `updated_by`.
- [x] **Statelessness**: The API layer holds no local state, allowing horizontal scaling (ShedLock guards the scheduler across instances).
- [ ] **Local Demoability**: `./dev.sh setup && ./dev.sh start && ./dev.sh seed` should bring up Postgres, Kafka, the API, Prometheus, and Grafana, and prove data flowing end-to-end (ingestion → categorization → query) with one command chain. What's actually verified in this environment (no Docker daemon available here, so only the Docker-independent paths could be run live): `./dev.sh certs` (generates a real CA + signed cert + working PKCS12 keystore — verified by decoding it with openssl), `.env` auto-generation (verified the generated secrets round-trip through `scripts/mint-jwt.mjs` correctly), and `./dev.sh test --unit` (verified green, **66/66** as of 2026-09-25, no Docker required — this run is also what caught and fixed a bad test in that subset's original list, see `docs/principal_engineer_review_report.md` §10.5). **2026-09-25 correction**: `dev.sh`'s `UNIT_ONLY_TESTS` list still named `SourceAdapterNormalizationTest`, a class deleted by `main`'s config-driven source registry rebase (ADR-011) and never replaced in this list — so `./dev.sh test --unit` was silently running a smaller set than intended (the previously-recorded 53/53 undercounted what's actually Docker-free). Fixed to `SourceNormalizerTest`, `HttpTransactionSourceTest`, `MockTransactionSourceTest`, `SyncHealthIndicatorTest` — the real current Docker-free set, re-verified green at 66/66. **Not yet run live: `start`, `stop`, `status`, `logs`, `seed`, `test` (full/`--e2e`), `perf`** — these are `bash -n`-clean and traced against verified endpoint/health-check shapes, but need someone with Docker to actually execute them before this box is checked, per the policy at the top of this document.

## 6. Definition of Done (DoD)

A phase is considered "Done" when:
1. All tasks in the phase are implemented.
2. Code is reviewed by at least one supporting agent from `claude.md`.
3. Integration tests pass in a Testcontainers environment.
4. OpenAPI spec is updated and reflects the actual implementation.
5. The Coordinator has signed off on the architectural consistency.
6. The Meta-Agent has run a retrospective, logged any recurring findings in `docs/retrospectives/LESSONS.md`, and proposed any resulting updates to `claude.md`.
