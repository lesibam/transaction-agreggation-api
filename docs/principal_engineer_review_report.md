# Principal Engineer Review Report — Transact (Transaction Aggregation API)

| | |
|---|---|
| **Date** | 2026-09-23 |
| **Reviewer** | Principal Engineer review (coordinator session) |
| **Subject** | `transaction-agreggation-api` — Java 21 / Spring Boot 4.1.1 / PostgreSQL 16 / Spring Kafka |
| **Baseline reviewed** | Working tree (no VCS baseline exists — see H-01) |
| **Method** | Full source review (72 main + 19 test classes), dependency/image inspection, two full `mvn test` runs, live docker-compose deployment, in-container HTTP probes, first genuine Playwright E2E execution |

> **Update (2026-09-24):** An independent follow-up review verified all three sign-off conditions below are now closed and found one new (non-blocking) scope finding. **Current verdict: Pass.** See §10 for the follow-up and §9 for the original conditions it discharges.

---

## 1. Executive Summary

**Verdict: Conditional Pass.** The product code is genuinely good — the domain invariants, idempotency design, failure taxonomy, and API contract discipline are at a level I rarely see in a first implementation pass. The verification story is now materially stronger than it was 24 hours ago: **90/90 unit/integration tests green** (run twice today) and **5/5 E2E tests green** against the real composed stack (first genuine run — see §6).

The conditions on the pass are **process and delivery gaps, not product defects**:

1. **The project is not under version control.** No git repository, no `.gitignore`, and a `.env` with (dev-only) secrets sits in the tree. A single `rm -rf` or a leaked `.env` and the work is gone. This is the single most urgent action item.
2. **The E2E harness could silently report a false green.** During this review, a host-port collision caused every "E2E" request to be answered by an unrelated local Spring service; the suite reported a clean exit 0 with 5/5 tests *skipped*. I root-caused this, hardened the harness (port pre-flight, `node:http` probe, loud skip warning), and re-ran for real: **5/5 passed against our app**. The harness fix must be preserved and CI must assert "zero skipped".
3. **CI exists on paper but cannot build confidence**: it has never run (no repo to push), its vulnerability scan is non-blocking, and it never verifies the packaged artifact (only `mvn verify`).

Severity-weighted counts: **1 Critical (process)**, **3 High**, **7 Medium**, **8 Low**. No product-code Critical findings. Details in §4; remediation roadmap in §8.

---

## 2. Scope and Method

### 2.1 What was reviewed

- Complete `src/main/java` (72 classes): domain, application, infrastructure, security, api, config
- Complete `src/test/java` (19 classes, 90 tests) and `tests/e2e/`
- `pom.xml`, `application.yml`, `logback-spring.xml`, Flyway V1/V2 migrations
- `Dockerfile`, `docker-compose.yml` (+ new `docker-compose.e2e.yml`), `prometheus/`, `grafana/`, `helm/transact/`
- `.github/workflows/ci.yml`, `scripts/`, `openapi.yaml`, `README.md`, `IMPLEMENTATION_PLAN.md`, `docs/` (incl. ADRs 001–010), `transaction-aggregation-staff-engineer-guide-2.md` (as the spec)

### 2.2 How it was verified (evidence base)

| Verification | Result |
|---|---|
| `mvn test` (full suite, Testcontainers PostgreSQL+Kafka) | **90/90 passed, BUILD SUCCESS** (run twice, 2026-09-23) |
| `docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build` | Stack healthy: postgres, kafka, transaction-api |
| In-container HTTP probe (`docker exec ... wget`) with minted JWT | **HTTP 200** with correct transaction payload, freshness, keyset cursor |
| Host-path HTTP probes through mapped port (after port fix) | Authed **200**; garbage token → **401 problem+json** with our plain `WWW-Authenticate: Bearer` |
| Playwright E2E (`npx playwright test`, 5 specs) | **5/5 passed** (888 ms) — first genuine full-stack run |
| Fat-jar classpath inspection (`unzip -l`, binary-safe `grep -a`) | 41,946 classes inspected for string provenance during incident forensics (§6) |

---

## 3. Verified Strengths

These are not compliments — each is verified against source or a running system:

1. **Idempotency is the database's job, not a check-then-insert race.** `UNIQUE(source_id, source_transaction_id)` (V1) is the sole source of truth. The Kafka listener classifies `DataIntegrityViolationException` narrowly — SQLState `23505` or explicit duplicate wording — and never blanket-catches (`TransactionIngestedListener.java:65-81`). Live system shows repeated cycles re-publishing the same mock records, absorbed silently as duplicates.
2. **Failure taxonomy is explicit and durable.** `ingestion_error` rows carry `VALIDATION`, `NO_ACCOUNT`, `AMBIGUOUS_ACCOUNT` (missing *and* ambiguous account mapping are both quarantined — `TransactionPersister.java:52-65`), or `INTEGRITY`; poison messages go to `transactions.dlq` after bounded exponential backoff (`KafkaConsumerConfig.java:61-68`). Quarantine writes happen *outside* the rolled-back persist transaction, in their own.
3. **Transactional correctness discipline.** The `@Transactional` boundary lives in a separate bean (`TransactionPersister`) precisely so the listener's proxy path is never self-invoked — the comment documents the reasoning (`TransactionPersister.java:20-23`).
4. **Monetary correctness.** `BigDecimal` end-to-end, `DECIMAL(19,4)` (`TransactionEntity.java:36-37`), `CHECK (amount > 0)` (V2), sign→direction normalization with `abs()` inside adapters (`SourceAAdapter.java:46-51`), per-currency SQL aggregates with **no FX conversion** (`TransactionRepository.java:72-96`).
5. **Keyset pagination done right.** Stable sort `(transaction_date DESC, id DESC)` matched by a V2 composite index `idx_transactions_customer_date`; opaque Base64URL cursor (`KeysetCursor.java:14-37`); `limit+1` read to compute `hasMore` (`TransactionQueryService.java:102-125`). Cursor decode is strict (rejects malformed with 400).
6. **Honest metadata.** Freshness severity ordered FRESH → STALE → VERY_STALE → UNKNOWN, aggregated with `max()` by severity (`TransactionQueryService.java:204-207`); completeness is `PARTIAL` whenever any configured source is not `SUCCESS`. The system structurally cannot present stale data as complete.
7. **Failure isolation per source.** One source throwing (after bounded, interrupt-aware exponential backoff — `IngestionService.java:180-207`) marks that source `FAILED` with `failure_count`/`last_error` and the cycle continues (`IngestionService.java:55-65`). `ResilienceTest` proves it.
8. **Clean port/adapter boundaries.** `TransactionSource` and `TransactionEventPublisher` are application-owned ports; normalization happens inside adapters; the broker is a swappable detail (ADR-009). The scheduler is ShedLock-guarded for horizontal scale (`IngestionScheduler.java:17-18`).
9. **Security model is simple and verifiable.** JJWT verification with issuer+audience required (`JwtTokenProvider.java:81-87`); roles normalized without `ROLE_` leakage; tenant isolation enforced server-side by `CustomerAccessValidator` (`sub` must equal path `customerId` unless ADMIN — `CustomerAccessValidator.java:16-31`), never trusted from the client. E2E proves: mismatched customer → 403 problem+json; customer-role token on `/v1/admin/**` → 403.
10. **Error contract discipline.** RFC 7807 `application/problem+json` everywhere, with `type` URIs, sanitized 500 detail (never `ex.getMessage()` on catch-all), and `traceId` copied from the `correlationId` MDC (`GlobalExceptionHandler.java:69-87`). Boot's competing `ProblemDetailsExceptionHandler` is explicitly outranked and this is documented (`GlobalExceptionHandler.java:25-31`).
11. **Ops baseline.** Structured JSON logs by default (`logback-spring.xml`), correlation IDs on every request (`CorrelationIdFilter`), Micrometer ingestion timers/counters tagged by source, actuator health with probe groups + per-source sync health, non-root multi-stage image (UID 10001), immutable `github.sha` image tags, Helm with secretKeyRef + probes/resources.
12. **Documentation honesty.** The `IMPLEMENTATION_PLAN.md` checkbox policy ("`[x]` only for verifiably delivered work") is actually enforced — SLI/SLO, K6, OTel, recovery drills, and CI scan enforcement are correctly left unchecked with precise caveats. Ten ADRs cover the real decision space.
13. **Test suite quality.** 90 deterministic tests: 21 categorizer, 10 adapter-normalization, 8 access-validator, 7+7+6+6+5+5 API-level with Testcontainers, plus quarantine/resilience/sync-failure/idempotency unit tests with no container dependency. No test bridges or shared mutable fixtures.

---

## 4. Findings Summary

| ID | Severity | Area | Finding | Status |
|----|----------|------|---------|--------|
| C-01 | **Critical** (process) | Verification | E2E harness can silently report a false green; a host-port collision went undetected and all E2E traffic hit an unrelated process | Harness hardened + E2E verified during review; CI wiring still required |
| H-01 | High | Delivery | Not a git repository; no `.gitignore`; `.env` with dev secrets on disk | Open |
| H-02 | High | CI/Security | Trivy scan is non-blocking (`continue-on-error`) — violates security standards | Open |
| H-03 | High | CI/Testing | No packaged-artifact verification in CI (compose smoke test); MockMvc slices don't cover the composed runtime | Open |
| M-01 | Medium | Config hygiene | Dead/misleading Kafka consumer props in `application.yml` (incl. `spring.json.trusted.packages: "*"`) | Open |
| M-02 | Medium | Consistency | Jackson 2 / Jackson 3 dual stack with two hand-built Jackson 2 mappers | **Fixed 2026-09-25** — see §5 |
| M-03 | Medium | Security clarity | `TenantContext` is a write-only ThreadLocal; `tenantId` claim never used for scoping | **Fixed 2026-09-25** — deleted, see §5 |
| M-04 | Medium | Fragility | `@Order(HIGHEST_PRECEDENCE)` coupling to Boot's ProblemDetailsExceptionHandler | **Re-scoped & fixed 2026-09-25** — real bug found underneath, see §5 |
| M-05 | Medium | Contract | `openapi.yaml` hand-maintained; no automated contract verification | Open |
| M-06 | Medium | Secrets | Helm chart materializes secrets from `.Values` into release metadata | **Partially fixed 2026-09-25** — see §5 |
| M-07 | Medium | Config | `spring.jpa.open-in-view` left default (WARN on every boot) | Open |
| L-01 | Low | Data | No `CHECK` constraint on `transactions.direction` (`DEBIT`/`CREDIT`) | **Fixed 2026-09-25** — see §5 |
| L-02 | Low | Ingestion | Mock adapters ignore the cursor for data generation → duplicate publishes every cycle | By design (mock), note |
| L-03 | Low | Tooling | `mint-jwt.mjs` silently falls back to the committed test secret | Open |
| L-04 | Low | Persistence | Summary SQL computes `debitCount`/`creditCount` that the mapping discards | **Fixed 2026-09-25** — see §5 |
| L-05 | Low | Observability | `CorrelationIdFilter` never echoes the correlation id in the response | Open |
| L-06 | Low | Infra | Kafka external listener advertises `localhost:9092` while e2e override maps host `9093` | Documented in override |
| L-07 | Low | Infra | Compose Grafana default `admin/admin` | Acceptable (dev-only) |
| L-08 | Low | Messaging | Topics auto-created (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`) | Acceptable (demo), note |

---

## 5. Detailed Findings

### C-01 — E2E harness can silently report a false green (Critical, process)

**Evidence.** The E2E suite is designed to "skip cleanly" when the app is not up. That design, combined with two harness defects, produced a **false green**: exit code 0 with `5 skipped`, while nothing was verified.

The full incident is documented in §6. In summary:
- `docker-compose.e2e.yml` (previous version) mapped the API to host port `8081` **without checking the port was free**. An unrelated local Java service already listened on `8081`. Docker reported the container healthy — because the container *was* healthy — but host traffic to `8081` never reached it.
- `tests/e2e/global-setup.ts` probed availability with global `fetch`. On this machine (Node 24.18.1), `fetch` against loopback times out even when a server answers (verified: `http.get` → 200, `fetch` → `TimeoutError` after 5 s). So even the impostor's health check failed, and all specs skipped.
- The suite then reported `PLAYWRIGHT_EXIT:0`.

**Why it matters more than a broken test.** The skip-soft pattern is defensible for a local-first suite (never breaks the Maven build) — but the failure mode is *invisible*: green means "not run" and nothing distinguishes the two. For a system whose headline promise is "never present partial results as complete", a verification harness that presents "not run" as "passed" is the same category of bug.

**Remediation applied during this review.**
1. `docker-compose.e2e.yml` now uses host port `18081` and documents a mandatory pre-flight (`lsof -nP -iTCP:18081 -sTCP:LISTEN` must be empty).
2. `global-setup.ts` now probes with `node:http` (immune to the undici/fetch loopback flakiness) and prints a loud warning when specs will be skipped.
3. `playwright.config.ts` and spec headers now state explicitly: *"a skipped result means NOT RUN — never treat it as green."*

**Remaining work (condition of the pass).** Wire E2E into CI against a compose stack, and add a post-run assertion that **zero tests were skipped** (e.g., parse the JSON reporter output and fail on any `status: "skipped"`).

---

### H-01 — No version control, no `.gitignore`, `.env` present (High)

**Evidence.** `git status` → `fatal: not a git repository`. Glob for `**/.gitignore` → no matches. A `.env` file (created for the E2E stack) contains dev-only secrets — precisely the file a future `git init && git add .` would swallow on day one.

**Risk.** Total loss of the deliverable on one bad command; no history, no review trail, no branch protection; the CI workflow (`.github/workflows/ci.yml`) triggers on `push` to branches that will never exist until this is fixed; accidental commit of `.env` the moment version control is initialized.

**Recommendation (P0, ~15 min).**
```bash
git init
cat > .gitignore <<'EOF'
.env
target/
node_modules/
playwright-report/
test-results/
*.log
EOF
git add -A && git commit -m "chore: baseline commit — Transact transaction aggregation API"
```
Then push to a remote and protect `main` before any further work.

---

### H-02 — CI vulnerability scanning is non-blocking (High)

**Evidence.** `.github/workflows/ci.yml:48` — `continue-on-error: true # non-blocking for now; tighten once baseline is clean`, with `exit-code: '0'` (line 52). Trivy therefore cannot fail the pipeline under any circumstances, including CRITICAL findings in base images or dependencies.

**Why it violates standards.** The org security standards require dependency and container vulnerability scanning as part of the pipeline; a scan whose result cannot change the pipeline outcome is a report, not a gate. The "tighten later" comment is the dangerous kind of TODO — later never arrives on its own.

**Recommendation.** Set `exit-code: '1'` for `CRITICAL,HIGH` with `ignore-unfixed: true` (already set), plus a committed, reviewed `.trivyignore` with expiry dates for accepted findings. This is a one-line change plus a baseline triage session.

---

### H-03 — No packaged-artifact verification in CI (High)

**Evidence.** CI runs `mvn -B verify` (`.github/workflows/ci.yml:23`) — excellent — but never boots the image it just built. All 90 unit/integration tests run through MockMvc slices and test contexts; none exercise the composed runtime (container networking, Flyway against a fresh volume, Kafka listeners, port mappings, health endpoints).

**Why it matters (proven today).** The §6 incident consumed significant debugging time precisely because the only composed-runtime evidence available was ad-hoc. A five-minute CI smoke job would have caught the discrepancy immediately and would have prevented the initial misattribution ("the app rejects valid JWTs") that only in-container probes disproved.

**Recommendation.** Add a CI job after image build:
1. `docker compose -f docker-compose.yml -f docker-compose.ci.yml up -d --wait` (CI ports),
2. `curl -f /actuator/health`, mint a JWT, `curl` one authenticated 200 and one 401 problem+json,
3. run `npx playwright test` with `E2E_BASE_URL` set and fail on any skipped test,
4. `docker compose down -v`.

This is the same harness the review just validated locally (5/5), so the job is a transcription task, not new engineering.

---

### M-01 — Dead/misleading Kafka consumer properties in `application.yml` (Medium)

**Evidence.** `application.yml:19-23` configures `spring.kafka.consumer.*` including `spring.json.trusted.packages: "*"` and `spring.json.value.default.type: ...`. The live consumer is *not* built from these — `KafkaConsumerConfig.java:40-55` constructs its own factory, pinning `JsonDeserializer.TRUSTED_PACKAGES` to `za.co.evilcorp.transact` (line 46) and the default type explicitly. The container log confirms the yml props are dead: *"These configurations '[spring.json.trusted.packages, spring.json.value.default.type]' were supplied but are not used yet."*

**Risk.** The file advertises wildcard package trust that the actual wiring does not use. If anyone later switches the listener to Boot's default container factory (a plausible refactor), they inherit `"*"` trust silently. Config that lies is worse than config that's absent.

**Recommendation.** Delete the four dead consumer-serializer lines from `application.yml` (keep `auto-offset-reset` only if desired as documentation, noting the factory also sets it), leaving `KafkaConsumerConfig` as the single source of truth.

---

### M-02 — Jackson 2 / Jackson 3 dual stack (Medium)

**Evidence.** Spring Boot 4 auto-configures Jackson 3 for HTTP message conversion, while production code still needs Jackson 2: `SecurityConfig` writes problem bodies with a Jackson 2 `ObjectMapper`, and Kafka serializers are Jackson 2. Two hand-built Jackson 2 mappers exist: `Jackson2Config.java:17-21` (injected into SecurityConfig and KafkaConsumerConfig) and an inline duplicate in `KafkaProducerConfig.java:33-34`. The `jackson-datatype-jsr310` dependency exists solely for this.

**Risk.** Serialization drift between the Kafka path, the error-writer path, and the HTTP DTO path (three different mappers with three different `java.time` behaviors). Today all observable paths are correct (API tests + E2E assert ISO-8601 shapes), so this is consistency debt, not a bug.

**Recommendation.** Consolidate: inject the shared Jackson 2 `ObjectMapper` bean into `KafkaProducerConfig` instead of building a second one; add a one-line ADR note documenting *why* Jackson 2 exists in a Boot 4 app (the comment in `Jackson2Config` is good — promote it to an ADR so the next engineer doesn't "clean it up").

> **Fixed 2026-09-25.** `KafkaProducerConfig.transactionEventProducerFactory` now takes `ObjectMapper objectMapper` as a parameter instead of building `new ObjectMapper()` + `registerModule(new JavaTimeModule())` inline — it's the same `Jackson2Config.jackson2ObjectMapper()` bean `SecurityConfig` and `KafkaConsumerConfig` already used, autowired by type (safe because Jackson 3 lives under a different package, `tools.jackson.*`, so there's exactly one bean of Jackson 2's `ObjectMapper` type in the context — no `@Qualifier` needed). `docs/adr/012-jackson-2-in-boot-4.md` written as recommended (numbered 012, not 011 — `main` independently claimed ADR-011 for the config-driven source registry during a rebase on 2026-09-25). Verified: `mvn compile` clean.

---

### M-03 — `TenantContext` is a write-only abstraction (Medium)

**Evidence.** `TenantContext.java:11-31` holds two ThreadLocals; `JwtAuthenticationFilter.java:62` is the only writer (`tenantContext.setTenant(...)`); the filter's `finally` clears it. A repo-wide grep finds **no readers**. The JWT `tenantId` claim is parsed, stored, and dropped on the floor.

**Risk.** Two readings, both bad: (a) if multi-tenant isolation is *supposed* to be enforced at the data layer, it isn't — isolation is real but purely `customerId`-based (which is currently sufficient since `CustomerAccessValidator` binds `sub` to the path); (b) if tenant scoping is *not* intended, the class and the claim are dead code that misleads auditors into believing a tenant control exists.

**Recommendation.** Decide. Either delete `TenantContext` (and document that tenancy is represented by `customers.tenant_id` and enforced at the customer-identity boundary), or make it real by adding `tenant_id` to the keyset/summary predicates. Given the guide's scope, deletion + documentation is the honest choice; note it in ADR-002.

> **Fixed 2026-09-25.** `TenantContext.java` deleted; `JwtAuthenticationFilter` no longer references it (the `sub`-claim `UUID.fromString` validation it used to gate on is preserved as an explicit check). Documented in `docs/03-architecture.md` §2.5 rather than ADR-002 — the security layer's own architecture section, which already described `TenantContext`, was the section that would otherwise still be wrong, and is where the next reader actually looks.

---

### M-04 — `@Order(HIGHEST_PRECEDENCE)` coupling to Boot internals (Medium)

**Evidence.** With `spring.mvc.problemdetails.enabled: true` (`application.yml:29-31`), Boot registers its own `ProblemDetailsExceptionHandler` at order 0. Our `GlobalExceptionHandler` must outrank it (`GlobalExceptionHandler.java:26-31`) or error bodies lose the `type` URIs and `traceId`. This ordering contract is fragile — a Boot upgrade that changes the default order silently degrades every error response.

**Mitigations already present.** The coupling is documented in the class comment and guarded by `ApiValidationTest` asserting full problem details (including `traceId`) on bad requests.

**Recommendation.** Choose one: (a) remove `spring.mvc.problemdetails.enabled` and own the problem+json rendering entirely, or (b) keep it and add a dedicated test asserting `GlobalExceptionHandler` wins the ordering (e.g., `@Order` value comparison or a `ProblemDetailsExceptionHandler`-specific 400 shape test). Option (a) is the lower-magic option consistent with the engineering principles.

> **Re-investigated 2026-09-25 — found a more important bug underneath this one, fixed that instead of the ordering.** Neither (a) nor (b) as originally framed, because tracing Spring's actual advice-resolution algorithm changes the picture: `ExceptionHandlerExceptionResolver` picks the *first* `@ControllerAdvice` bean (in `@Order`) that has *any* matching `@ExceptionHandler` method — it does not compare specificity across beans. `GlobalExceptionHandler` has an `Exception.class` catch-all, which matches *every* exception type. That means it **always** wins over Boot's `ProblemDetailsExceptionHandler`, for every exception, regardless of the exact `@Order` value, as long as it's ordered ahead at all — `HIGHEST_PRECEDENCE` (`Integer.MIN_VALUE`) is in fact about as future-proof as Spring ordering gets; the "silent degradation on a Boot upgrade" risk the original finding worried about is smaller than it looked.
>
> The real bug the catch-all was hiding: Boot's `ProblemDetailsExceptionHandler` — never actually reached — normally gives correct status codes to several Spring MVC exceptions this app's own advice didn't explicitly handle. Concretely, before this fix, a **wrong HTTP method** (`HttpRequestMethodNotSupportedException`), an **unsupported content type** (`HttpMediaTypeNotSupportedException`), and an **entirely unmapped route** (`NoResourceFoundException`) were all silently falling into the `Exception.class` catch-all and returning a generic `500 Internal Server Error` instead of the correct `405`/`415`/`404`. **Fixed**: three explicit `@ExceptionHandler` methods added for these types in `GlobalExceptionHandler`, using the same `problem()` helper as everything else. Left `@Order(HIGHEST_PRECEDENCE)` and `spring.mvc.problemdetails.enabled` untouched — changing either would have zero effect on behavior today (fully shadowed either way) and only added risk for no correctness gain. Added `ApiValidationTest.wrongHttpMethodReturnsProblemDetail405` and `.unknownRouteReturnsProblemDetail404`. Verified: `mvn test-compile` clean, `./dev.sh test --unit` still green. The two new tests themselves need Testcontainers to run — not executed live in this environment (no Docker); a reviewer with Docker should confirm both pass.

---

### M-05 — Hand-maintained `openapi.yaml` with no drift detection (Medium)

**Evidence.** The committed contract is hand-written (a deliberate, documented choice — no springdoc). Reviewer round 1 already caught real drift in it (exclusive/inclusive `endDate`, nested merchant/category shapes, admin sources fields) — proof that hand-maintenance drifts. Nothing in CI validates spec-vs-implementation.

**Recommendation.** Keep the hand-written spec (generated specs are noise for consumers), but add a thin contract test: an API test that hits the three endpoints and asserts field names against the schemas' property lists read from `openapi.yaml` at test time (a ~50-line utility). That converts drift from "next reviewer catches it" to "CI catches it".

---

### M-06 — Helm chart bakes secrets into release metadata (Medium)

**Evidence.** `helm/transact/templates/secret.yaml:8-10` materializes `SPRING_DATASOURCE_PASSWORD` and `APP_SECURITY_JWT_SECRET` from `.Values.secret.*`. Helm stores rendered values in release metadata (and often in CI shell history via `--set`).

**Recommendation.** For the demo cluster this is tolerable and clearly labeled; before any production claim, switch to ExternalSecrets/SealedSecrets or at minimum document a rotation procedure and forbid `--set secret.*` in runbooks. Add a values schema that refuses empty secret values (fail helm template early).

> **Partially fixed 2026-09-25.** The "refuses empty secret values" half is done: `helm/transact/templates/secret.yaml` now calls `fail` if either secret is empty *or* still equal to its `values.yaml` placeholder default, so a `helm install` with no `--set` overrides errors out instead of silently shipping `change-me-dev-only` to a real cluster. `.github/workflows/ci.yml`'s `deploy-demo` job updated to pass `secret.springDatasourcePassword`/`secret.appSecurityJwtSecret` from two new GitHub Actions secrets it didn't need before (`DEMO_SPRING_DATASOURCE_PASSWORD`, `DEMO_APP_SECURITY_JWT_SECRET`) — **neither is configured on this repo today**, so if `KUBECONFIG` is ever added to actually enable that job, these two must be added alongside it or the Helm render will fail by design. **Not fixed**: the underlying ExternalSecrets/SealedSecrets migration and a documented rotation procedure — real infra work needing a target platform, still open. **Unverified**: no `helm` binary was reachable in this environment (egress to `get.helm.sh` and `github.com/helm/helm/releases` both blocked by the proxy policy) — the template change was written against well-established Helm/Sprig syntax (`fail`, `empty`, `eq`, `or`) but never run through `helm template`/`helm lint`. Run `helm lint ./helm/transact` and `helm template ./helm/transact --set secret.springDatasourcePassword=x --set secret.appSecurityJwtSecret=y` before trusting this in CI.

---

### M-07 — `spring.jpa.open-in-view` left at default (Medium-Low)

**Evidence.** Every boot logs the OSIV WARN (`spring.jpa.open-in-view is enabled by default...`). The engineering principles say minimize framework magic; the query layer doesn't need OSIV (all reads happen inside services).

**Recommendation.** `spring.jpa.open-in-view: false` in `application.yml`. One line, removes a startup warning and a latent lazy-loading hazard.

---

### L-01 — No `CHECK` on `transactions.direction`
V1 declares `direction VARCHAR(10)` with a comment — not a constraint (`-- 'DEBIT' or 'CREDIT'`). V2 added a status CHECK to `source_sync_state` but not to `transactions`. The enum mapping makes bad values unlikely, but invariants belong in the database per the DBA rules. Add `CHECK (direction IN ('DEBIT','CREDIT'))` in the next migration.

> **Fixed 2026-09-25.** `V4__transactions_direction_check.sql` adds `CHECK (direction IN ('DEBIT', 'CREDIT'))`, matching V2's `source_sync_state.status` pattern exactly. `hibernate.ddl-auto: validate` doesn't validate CHECK constraints (no `@Check` mapping exists in the entity), so this can't cause a startup validation failure. Not yet run against a live Postgres in this environment (no Docker) — the SQL is a direct copy of an already-proven pattern, but a reviewer with Docker should still run `./dev.sh start` once and confirm Flyway applies it cleanly. (Numbered V4, not V3 — `main` independently claimed `V3__source_d_demo_account.sql` during a rebase on 2026-09-25; the two migrations are unrelated and both apply cleanly in sequence.)

### L-02 — Mock adapters ignore the cursor
`SourceAAdapter.simulateApiCall` (lines 60-78) returns the same records regardless of cursor, so every 60 s cycle re-publishes known records and relies on idempotency to skip them. Correct and self-proving — but it also means the live system emits a stream of duplicate-key WARNs forever (observed in container logs). For demo realism and log hygiene, make adapters return an empty page when the cursor indicates "already seen", or lower the Hibernate WARN for constraint 23505. Document whichever is chosen.

### L-03 — `mint-jwt.mjs` silently falls back to the committed test secret
`scripts/mint-jwt.mjs:17-18` — the fallback matches the surefire test secret by design, but a silent fallback means an engineer can unknowingly mint tokens against the wrong environment. Print a warning to stderr when the env var is absent, or require it explicitly outside a dev context.

### L-04 — Dead aggregate columns
`TransactionRepository.java:77-78` computes `debitCount`/`creditCount`; `TransactionQueryService.toCurrencyTotals` (150-154) drops them. Either surface them in `SummaryDto` (cheap, and counts are genuinely useful) or remove from the SQL.

> **Fixed 2026-09-25.** Surfaced, not removed. Threaded through the whole chain: `CurrencySummaryRow` gained `getDebitCount()`/`getCreditCount()` (the SQL columns existed but nothing bound to them — Spring Data's native-query projection matches columns to interface getters by name, so a missing getter silently drops the column at the JDBC boundary, before it ever reaches Java), `CurrencyTotals` and `SummaryDto.CurrencySummary` gained matching fields, `openapi.yaml` documents them. Test coverage added in `ApiSummaryCurrencyTest.returnsSeparateTotalsPerCurrency`. Verified: `mvn test-compile` clean across the full chain; `./dev.sh test --unit` still green. The new assertions themselves need Testcontainers to actually run (no Docker in this environment) — a reviewer with Docker should confirm the test passes, not just compiles.

### L-05 — Correlation id is not echoed to clients
`CorrelationIdFilter.java:28-33` sets the MDC only. During the §6 incident, the response header `X-Correlation-Id` I observed actually came from the *impostor* application — our app does not echo one. Add `((HttpServletResponse) response).setHeader(CORRELATION_ID_HEADER, correlationId)` so clients can quote the trace id in support tickets (the ProblemDetail `traceId` extension covers errors; normal 200s currently carry no correlation).

### L-06 — Kafka advertised listener vs e2e host mapping
`docker-compose.yml:36` advertises `EXTERNAL://localhost:9092`; the e2e override maps host `9093->9092`. Host-side Kafka *tools* would receive advertised metadata pointing at the wrong port. The app is unaffected (uses internal `kafka:29092`). Now documented in the override file; optionally parameterize the advertised port for a fully consistent setup.

### L-07 — Compose Grafana default credentials
`docker-compose.yml:82-83` (`admin/admin`). Dev-only, acceptable; keep it out of any environment resembling shared infrastructure.

### L-08 — Topics auto-created
`KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"` plus `auto-create` on the client. Fine for the demo; production should provision `transactions.ingested` (partitions, replication, retention) and `transactions.dlq` explicitly via IaC so the DLQ recoverer's partition assumption (`record.partition()` — `KafkaConsumerConfig.java:64-66`) holds by construction rather than by broker defaults.

---

## 6. Incident Post-mortem: The Port Collision That Looked Like an Auth Bug

Recorded because the *pattern* is more instructive than the bug, and because the report must be honest about what the reviewer believed along the way.

**Timeline.**
1. Compose stack started with the API mapped to host `8081` (chosen to avoid `billing-service` on 8080 and `oneazui-kafka` on 9092). Compose reported the container healthy — it was.
2. Host-side probes showed: `GET /actuator/health` → 200 with plausible Spring actuator JSON; authenticated API calls → **401** with `WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: Signed JWT rejected: Another algorithm expected, or no matching key(s) found", ... resource_metadata="http://127.0.0.1:8081/.well-known/oauth-protected-resource"` — empty body, RFC 9728 style.
3. Working hypothesis (reasonable, wrong): a runtime-only failure in our JWT validation, possibly a Spring Security 7.1 resource-server auto-configuration. Evidence gathered: secrets matched byte-for-byte across `.env`, container env, and mint script; the token was HS384 exactly as JJWT would choose; unit tests with the same provider pass; no `Rejected JWT` logs from our filter; no OAuth2 resource-server jar in the fat jar; and — decisively — **binary-safe grep across all 41,946 classes in the fat jar found none of the emitted error strings.** The application physically could not have produced those responses.
4. `lsof -nP -iTCP:18081` (well: `8081` at the time) revealed the answer: the listener was a **local Java process, PID 37986 — not the Docker proxy**. An unrelated Spring service (with real OAuth2 resource-server security) owned host port 8081; every "E2E" request had been answered by it. Its actuator health shape (`{"groups":[...],"status":"UP"}`) was coincidentally compatible with ours, which made the impostor convincing.
5. Falsification test: `docker exec ... wget --header="Authorization: Bearer <minted>" http://localhost:8080/v1/...` from *inside* the container → **HTTP 200** with correct payload. The app was innocent.
6. Harness hardened (C-01), port moved to pre-flighted `18081`, E2E re-run → **5/5 passed**.

**Lessons for the team.**
- Port pre-flight is not optional on developer machines; docker reports container health, not *host-path reachability* of the mapped port.
- "Skip on unavailable" harnesses must be loud; a skipped suite is indistinguishable from a passing one in exit codes.
- In-container probes are the ground truth for containerized services; host-path evidence is one hop too far away to trust during debugging.
- Response-format forensics (whose strings are these?) beat speculation; `grep -a` over the extracted classpath settled a question that logs could not.

---

## 7. Assessment by Dimension

| Dimension | Rating | Notes |
|---|---|---|
| Architecture & boundaries | 9/10 | Modular monolith with real ports; broker swappable; layers respected; only blemish is `TenantContext` (M-03) |
| Domain correctness | 9/10 | Identity, idempotency, categorization versioning, explainability (`rule_id`), monetary precision all per spec; missing direction CHECK (L-01) |
| Security | 7.5/10 | Solid JWT + RBAC + tenant-identity isolation, proven end-to-end; gaps are process-side (scan enforcement H-02, Helm secrets M-06) plus config honesty (M-01) |
| Data & consistency | 9/10 | Constraint-driven idempotency, quarantine + DLQ, honest freshness/completeness |
| Observability & ops | 7/10 | Good metrics/logging/health baseline; missing dashboards, alert rules, consumer-lag metrics, OTel (all honestly tracked as open) |
| Testing | 8/10 | 90/90 unit/integration + now 5/5 E2E; gaps: packaged-artifact CI job (H-03), load testing, fault injection |
| API & contract | 8/10 | RFC 7807 discipline, keyset pagination, inclusive-boundary semantics consistent across code/docs/spec after round-1 fixes; drift detection still manual (M-05) |
| Delivery & process | 4/10 | No VCS (H-01), never-run CI, non-enforced scanning; documentation quality itself is high (10 ADRs, honest plan) |

---

## 8. Prioritized Remediation Roadmap

**P0 — before any further feature work (~half a day total)**
1. `git init`, `.gitignore` (`.env`, `target/`, `node_modules/`, `playwright-report/`, `test-results/`), baseline commit, push to remote, protect `main`. (H-01)
2. CI: make Trivy blocking (`exit-code: 1`, CRITICAL/HIGH, `ignore-unfixed` + reviewed `.trivyignore`). (H-02)
3. CI: add the compose smoke + Playwright job with a **zero-skipped assertion** — the harness validated locally today makes this a transcription. (H-03, C-01 remainder)

**P1 — next sprint**
4. Delete dead Kafka consumer props from `application.yml`; single-source the factory. (M-01)
5. Decide `TenantContext`: delete + document, or enforce. (M-03)
6. OpenAPI drift test (spec-driven property assertions). (M-05)
7. Consolidate Jackson 2 mappers; ADR-012 on Jackson 2-in-Boot-4. (M-02)
8. OSIV off. (M-07)

**P2 — backlog**
9. Direction CHECK constraint (L-01); dead count columns (L-04); correlation echo (L-05); mint-script guard (L-03); adapter cursor realism (L-02).
10. The honestly-tracked items: SLI/SLO + dashboards + alert rules, K6 load profile, OTel propagation, recovery drill (RPO/RTO verification), topic provisioning via IaC (L-08), Helm external secrets (M-06).

---

## 9. Verdict & Sign-off Conditions

**Conditional Pass.**

The implementation meets the Staff Engineer Guide's bar on every dimension it claims to meet, and the honesty of its tracking documents means what remains unmet is *visible* rather than discovered. The two prior review rounds' findings (constructor injection, ambiguous-account quarantine, exception-handler ordering, OpenAPI/docs reconciliation) are verified fixed in the tree and by the green suites.

Sign-off is withheld pending exactly three conditions — all process, all small:

1. Version control initialized with a protected `main` and a committed baseline.
2. Vulnerability scanning enforced in CI.
3. The E2E/compose verification wired into CI with a no-skipped assertion.

When those land, this codebase is in the top tier of what I would expect to hand to production engineering for the next stage (real source adapters, SLIs, load profiles) — the foundations (idempotency, honesty of metadata, failure taxonomy) are the hard parts, and they are right.

---

## 10. Follow-up Review — 2026-09-24 (Independent Verification)

**Reviewer.** A separate principal-engineer pass, one day later, in a sandboxed review environment with **no Docker daemon available** (`docker info` fails). This constrains the method: git history, the CI workflow definition, and static source/config inspection substitute for live compose/Testcontainers runs. Where that matters, it is called out explicitly rather than silently assumed.

### 10.1 P0 remediation — verified CLOSED

All three sign-off conditions from §9 are independently confirmed in the tree, not just claimed in `IMPLEMENTATION_PLAN.md`:

| Condition | Evidence | Status |
|---|---|---|
| H-01 version control | `git log --oneline` shows 5 commits from `12fa9b1` (baseline) through `d1f7ae6`; a real history exists, `.env` is not tracked | **Closed** |
| H-02 blocking Trivy | `.github/workflows/ci.yml:50-57` — `exit-code: '1'`, `severity: CRITICAL,HIGH`, `ignore-unfixed: true`; commit `4807e23` shows this gate actually caught and drove a fix (embedded Tomcat CVEs) | **Closed** |
| H-03 / C-01 composed-runtime + zero-skipped E2E in CI | `.github/workflows/ci.yml:81-139` (`e2e-smoke` job) boots the real compose stack, asserts the auth/error contract over HTTP, then runs Playwright through `scripts/e2e-assert.mjs` which fails the job on any `status: "skipped"` | **Closed** |

This is real remediation, not documentation theater — each fix is a genuine control (a failing exit code, a real HTTP assertion), not a comment saying it was done.

### 10.2 P1/P2 findings — re-checked, unchanged

Every Medium/Low finding from §4 was re-verified directly against current source. All are **still open**, byte-for-byte as originally described — three commits landed since baseline and all three were process/CI-only (`582831f`, `4807e23`, `d1f7ae6`); none touched application code:

- **M-01** — `application.yml:22-23` still sets `spring.json.trusted.packages: "*"` / a default type, dead against `KafkaConsumerConfig`'s own factory. Unchanged.
- **M-03** — `TenantContext` still has exactly one writer (`JwtAuthenticationFilter`) and zero readers repo-wide. Unchanged.
- **M-07** — `spring.jpa.open-in-view` is still absent from `application.yml` (Boot default `true` still applies, still WARNs on boot). Unchanged.
- **L-01** — `transactions.direction` is still a bare `VARCHAR(10)` with a comment, no `CHECK`, in both V1 and V2. Unchanged.
- **L-03** — `scripts/mint-jwt.mjs:17-18` still falls back to the committed test secret with no stderr warning. Unchanged.
- **L-04** — `TransactionRepository.java:77-78` still computes `debitCount`/`creditCount`; `SummaryDto.CurrencySummary` still has no fields for them. Unchanged.
- **L-05** — `CorrelationIdFilter.java` still only writes MDC; it never calls `response.setHeader(...)`. Unchanged.

**This is itself the finding.** The prior report's P0/P1/P2 tiering worked exactly as designed — the three items gating sign-off got fixed, and the rest did not move, because nothing forces them to. That is a reasonable prioritization outcome for a first pass, but if it repeats across further review cycles, "Medium" becomes a polite synonym for "never." See the logged lesson in §10.5.

### 10.3 New finding: N-01 — Helm/Kubernetes deployment contradicts the guide's own explicit scope guidance (Medium, Architecture/Scope)

**Evidence.** `transaction-aggregation-staff-engineer-guide-2.md` §85 ("What Not to Build") lists **Kubernetes** first among the things to avoid "unless the requirements specifically demand them," framing the exercise as demonstrating *restraint*, not technology breadth. §96K ("Deployment Environments") is concrete about what "demo" should be: *"demo: Docker Compose or small VPS."* §96L reinforces it: *"If the assessment requires a deployed demonstration, a small Linux VPS is sufficient."*

The delivered system instead ships a full Helm chart (`helm/transact/templates/{deployment,service,secret,...}.yaml`) and a CI job (`deploy-demo` in `.github/workflows/ci.yml:147-167`) that runs `helm upgrade --install` against a Kubernetes cluster for the demo environment — the exact shape of scope the guide asks candidates to justify or avoid.

**Why it matters.** Nothing in `docs/adr/` addresses *why* Kubernetes was chosen for a demo tier the guide explicitly says doesn't need it (grep of `docs/adr/*.md` for "kubernetes"/"helm" — no results). Compare this to how well-justified the rest of the stack is: ten ADRs cover Postgres, eventual consistency, idempotency, the messaging port, etc., each closing with "why not the alternative." Kubernetes is the one infrastructure decision in the tree with no ADR and no justification against the guide's own checklist — it reads as reached-for rather than chosen. This doesn't cost correctness points, but it does cost the "knows when not to use it" signal the guide says is the actual thing being assessed (§85: *"The goal is not to demonstrate how many technologies you can deploy... The goal is to demonstrate that you know when not to use them."*).

**Recommendation.** Either (a) add a new ADR (next available number — 011 and 012 are now taken by the config-driven source registry and the Jackson 2-in-Boot-4 decision) stating the concrete reason Kubernetes is in scope (e.g., "target platform is already Kubernetes at evilcorp, demo must match production" — if true, this is a good reason and just needs to be written down), or (b) keep the Helm chart as documented **future evolution** (it's genuinely fine engineering) but stop running `deploy-demo` by default — point the demo tier at Compose on a VPS per §96L, matching what the guide actually asked for.

### 10.4 Correction to §7: duplicate records ARE measurable

The original review's Assessment by Dimension didn't call this out explicitly, and the Design Review Checklist item ("Are duplicate records measurable?") deserves a direct answer: **yes** — `TransactionIngestedListener.java:50` increments `transact.ingestion.records.duplicates` (tagged by `source`) every time the unique-constraint path is taken, alongside `transact.ingestion.records.received/published/quarantined` and `transact.ingestion.source.sync.success/failure` in `IngestionService.java`. This is a real strength that should be listed alongside the other verified-metrics items in §3.11, not left implicit.

### 10.5 Verification note on this pass's own limits

`mvn compile` succeeded cleanly. Of the 9 test classes that don't require Testcontainers/`@SpringBootTest`, running them directly (`KeysetCursorTest`, `ResilienceTest`, `QuarantineTest`, `IdempotencyTest`, `SyncFailureTest`, `RuleBasedCategorizerTest`, `CustomerAccessValidatorTest`, `SourceAdapterNormalizationTest`, plus `IngestionServiceTest` which turned out to need a full Spring context) produced **56 run, 53 passed, 3 errors** — the 3 errors are `IngestionServiceTest` failing application-context startup, consistent with the missing datasource/Kafka broker in this sandbox (no Docker), not a code regression. The full 90-test Testcontainers suite and the composed-runtime E2E smoke were **not** re-run here; §10.1's confidence rests on CI's own gate definition and commit history, not a fresh live run. A reviewer with Docker access should still do one.

> **Correction (2026-09-25, while building `dev.sh`):** `IdempotencyTest` does *not* belong in that "doesn't require Testcontainers" list — it `extends AbstractIntegrationTest`, which is `@SpringBootTest` + shared Testcontainers Postgres/Kafka. It happened to pass in the run above only because `IngestionServiceTest` hit the "no Docker" failure first in that particular execution order and absorbed all 3 reported errors; re-running the corrected 7-class subset (dropping both `IdempotencyTest` and `IngestionServiceTest`) is genuinely container-free and passed cleanly, 53/53, in this same Docker-less environment. `dev.sh`'s `UNIT_ONLY_TESTS` list uses the corrected 7 classes.
>
> **Further correction (2026-09-25, later the same day, post-rebase):** the 7-class list above included `SourceAdapterNormalizationTest`, which `main`'s config-driven source registry (ADR-011) deleted — that class no longer exists in the tree this branch was rebased onto. `./dev.sh test --unit` was consequently running one fewer class than intended, silently, with no error (a missing `-Dtest` pattern just contributes nothing rather than failing). Fixed in `dev.sh` to the actual current Docker-free set — `SourceNormalizerTest`, `HttpTransactionSourceTest`, `MockTransactionSourceTest`, and `SyncHealthIndicatorTest` (all new classes the same registry refactor added) replace it. Re-verified green: **66/66**. See `IMPLEMENTATION_PLAN.md`'s Local Demoability item for the current authoritative count — this report's own numbers above (53/53, 56 run/53 passed/3 errors) remain accurate as historical record of the 2026-09-24 run they describe, not of the tree as it stands today.

### 10.6 Updated Assessment by Dimension

| Dimension | Prior (2026-09-23) | Now (2026-09-24) | Why it moved |
|---|---|---|---|
| Delivery & process | 4/10 | **9/10** | All three P0 gates (VCS, blocking scan, zero-skipped composed E2E in CI) verified closed with real controls, not just claims |
| Architecture & boundaries | 9/10 | **8/10** | Same strengths (TenantContext aside) minus one point for N-01 — the one infrastructure choice in the tree without an ADR, and one the guide explicitly flags |
| All other dimensions | as §7 | **unchanged** | No application code changed since the baseline review; re-verified, not re-scored |

### 10.7 Updated Verdict

**Pass** (upgraded from Conditional Pass). All three sign-off conditions from §9 are met. Remaining Medium/Low items are real but non-blocking technical debt, explicitly tracked, none of them safety- or correctness-critical. The one new item (N-01) is a scope-justification gap, not a defect — closing it is a paperwork fix (write the ADR) or a scope-reduction fix (stop deploying to K8s by default), not an engineering one.

### 10.8 Lessons logged

Per the Continuous Improvement Loop (`claude.md` §11, added 2026-09-24), the following were logged to `docs/retrospectives/LESSONS.md` as a result of this review: non-blocking findings not converging across cycles without a forcing function, and new infrastructure not being checked against the guide's own "What Not to Build" list before being added. One resulting change was applied directly to the Architect agent's definition in `claude.md` (see the lessons log for the exact diff and rationale).

---

## 11. Remediation Session — 2026-09-25

§10.2 predicted exactly this outcome: *"'Medium' becomes a polite synonym for 'never'... unless something forces it."* This session is that forcing function — every Medium/Low finding from §4 was worked through directly, not just re-logged.

**Closed (10 of 10 findings addressed):**

| ID | Outcome |
|---|---|
| M-01 | Fixed — and turned out worse than reported: not just two dead `properties.*` lines but the *entire* `spring.kafka.consumer`/`producer` block was dead. Removed. |
| M-02 | Fixed — consolidated onto the one shared Jackson 2 bean; `docs/adr/012-jackson-2-in-boot-4.md` added. |
| M-03 | Fixed — `TenantContext` deleted (not enforced), per the review's own recommended path. |
| M-04 | Fixed — and turned out to be a different, more important bug than reported: not really an ordering fragility (the catch-all made `@Order`'s exact value moot), but three real exception types silently returning `500` instead of `405`/`415`/`404`. |
| M-06 | Partially fixed — fail-fast Helm guard added; the real ExternalSecrets/KMS migration stays open (genuine infra work). |
| M-07 | Fixed — verified safe first (no entity has a JPA association for OSIV to matter to). |
| L-01 | Fixed — `V4__transactions_direction_check.sql`. |
| L-03 | Fixed — stderr warning on secret fallback. |
| L-04 | Fixed — `debitCount`/`creditCount` threaded through the full stack instead of discarded at the JDBC projection boundary. |
| L-05 | Fixed — correlation ID echoed on every response. |

**Still open, unchanged, and correctly so:** L-02 (mock adapters ignore cursor — by design), L-06/L-07/L-08 (documented/acceptable for a demo topology). All of Phase 9 ("Bank Production Readiness") remains open — none of today's fixes were an attempt at that bar, and `IMPLEMENTATION_PLAN.md` was updated where a Phase 9 line referenced a now-partially-stale finding (M-03, M-06).

**Pattern worth naming, since it happened twice in one session:** two of these findings (M-01, M-04) were *understated* by the original review — the actual root cause, once traced fully, was bigger than what got written down. Both times, fixing the finding as literally described would have been a smaller, less valuable fix than what the code actually needed. Worth remembering for future review rounds: a finding's initial framing is a starting point for investigation, not a fixed scope for the fix.

**What's genuinely verified vs. not.** `mvn compile`/`test-compile` ran clean after every change; the 53-test Docker-free subset (`./dev.sh test --unit`) passed after every batch. Nothing requiring Testcontainers or a live Postgres/Kafka (the new migration, the new/changed integration tests, the summary API's new fields end-to-end) has run live — no Docker daemon was available in this session, same limitation as §10.5. The Helm change is additionally unverified against `helm template`/`helm lint` — no `helm` binary was reachable (egress to `get.helm.sh` and `github.com/helm/helm/releases` both blocked by proxy policy). **A reviewer with Docker and Helm should run the full suite and `helm lint` before treating this session's changes as done, not just committed.**

---

## Appendix A — Verification Evidence Log (2026-09-23)

| # | Command / Probe | Result |
|---|---|---|
| 1 | `mvn test` (full suite; Testcontainers PG+Kafka; RYUK disabled, Rancher socket) | 90/90, BUILD SUCCESS (run twice) |
| 2 | `docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build` | 4 services healthy (after port re-map) |
| 3 | `curl :18081/actuator/health` | 200 `{"groups":["liveness","readiness"],"status":"UP"}` |
| 4 | `docker exec ... wget --header="Authorization: Bearer <mint-HS384>" localhost:8080/v1/customers/{demo}/transactions?limit=1` | **200** with payload, `meta.freshness.status=FRESH`, `completeness=COMPLETE`, valid keyset cursor |
| 5 | `curl :18081 ... -H "Authorization: Bearer not.a.jwt"` | **401**, `WWW-Authenticate: Bearer`, problem+json (ours — not the impostor's RFC-9728 format) |
| 6 | `lsof -nP -iTCP:8081 -sTCP:LISTEN` (incident) | `java PID 37986` — unrelated local process (impostor) |
| 7 | Binary-safe string search across 41,946 fat-jar classes for emitted 401 phrases | **0 matches** — strings not in our artifact (proved impostor provenance) |
| 8 | `E2E_BASE_URL=http://127.0.0.1:18081 npx playwright test` | **5 passed** (888 ms), 0 skipped |

## Appendix B — Files Reviewed (principal paths)

- **Domain**: `CanonicalTransaction`, `Category`, `TransactionDirection`, `TransactionCategorizer`, `RuleBasedCategorizer`, `DomainConfiguration`
- **Application**: `IngestionService`, `TransactionPersister`, `TransactionQueryService`, `KeysetCursor`, `IngestionScheduler`, ports (`TransactionSource`, `TransactionEventPublisher`, `TransactionIngestedEvent`), `application.dto.*`
- **Infrastructure**: `KafkaTransactionEventPublisher`, `TransactionIngestedListener`, `KafkaConsumerConfig`, `KafkaProducerConfig`, `SourceA/B/CAdapter`, `SyncCursor`, `integration.dto.*`, persistence entities + repositories, `CorrelationIdFilter`, `SyncHealthIndicator`
- **Security**: `SecurityConfig`, `JwtTokenProvider`, `JwtAuthenticationFilter`, `CustomerAccessValidator`, `TenantContext`
- **API**: `TransactionController`, `AdminSourceController`, `GlobalExceptionHandler`, `api.dto.*`
- **Config/Infra**: `pom.xml`, `application.yml`, `logback-spring.xml`, `Dockerfile`, `docker-compose.yml`, `docker-compose.e2e.yml`, `prometheus/prometheus.yml`, `grafana/provisioning/*`, `helm/transact/**`, `.github/workflows/ci.yml`
- **Tests/E2E/tooling**: all 19 test classes, `tests/e2e/*`, `playwright.config.ts`, `scripts/mint-jwt.mjs`, `scripts/test.sh`
- **Schema**: `db/migration/V1__initial_schema.sql`, `V2__enhance_schema.sql`
- **Docs**: `README.md`, `openapi.yaml`, `IMPLEMENTATION_PLAN.md`, `docs/01-04`, `docs/adr/001-010`, `docs/recovery-plan.md`

## Appendix C — Findings From Earlier Review Rounds (Verified Fixed)

These were raised by prior reviewer rounds and are confirmed resolved in the current tree; listed so the record is complete:

1. `IngestionService` constructor injection for retry parameters (was `@Value` field injection) — fixed (`IngestionService.java:38-53`), tests updated to constructor args.
2. Ambiguous-account quarantine (`AMBIGUOUS_ACCOUNT`) added alongside `NO_ACCOUNT`; `AccountRepository.findAllBySourceProvider` returns a list (`TransactionPersister.java:49-65`).
3. `GlobalExceptionHandler` outranks Boot's ProblemDetailsExceptionHandler and handles `TypeMismatchException`/`HttpMessageNotReadableException` (`GlobalExceptionHandler.java:36-49`) — enum-conversion 400s now carry `type` + `traceId` (test-asserted).
4. `/actuator/prometheus` permitAll aligned with the actual scrape config (`SecurityConfig.java:46`).
5. OpenAPI/docs reconciliation: inclusive `endDate` on both list and summary; nested `merchant`/`category` schemas; `SummaryResponse.meta` → ListMeta; admin sources response matches `SourceHealthDto`; README JWT minting references the real script.
6. POM hygiene: `spring-boot-starter-flyway`, `spring-boot-starter-kafka`, `jackson-datatype-jsr310`, and the Boot-4 test starters (`data-jpa-test`, `jdbc-test`, `webmvc-test`, `security-test`) — verified present.
7. Kafka producer/consumer explicitly wired (no type headers, pinned trusted packages, shared JavaTime handling) — verified live: ingestion cycle publishes, consumer persists, duplicates skipped by constraint.
