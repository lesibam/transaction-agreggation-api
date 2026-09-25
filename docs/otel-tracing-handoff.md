# Handoff: OpenTelemetry Distributed Tracing

| | |
|---|---|
| **From** | Architect, on behalf of Backend Engineer (HTTP-side instrumentation — close to free) and Integration Engineer (Kafka-side span propagation — the harder, more valuable half) |
| **To** | Operations/SRE Engineer |
| **Status** | **Proposed.** Closes `IMPLEMENTATION_PLAN.md` Phase 6's "Distributed Tracing" item and the "Span IDs require the OpenTelemetry item above — not delivered" caveat on Structured Logging. Verified: no `opentelemetry`/`micrometer-tracing` dependency exists in `pom.xml` today — this is a from-scratch addition, not a config flip on something half-built. |

---

## 1. Why this matters more for ingestion than for the query API

The query API (`GET /v1/customers/{id}/transactions`) is already reasonably traceable: one HTTP request, one thread, `CorrelationIdFilter` sets `correlationId` in MDC for the whole request lifecycle, and it's already echoed on the response and copied into every `ProblemDetail`'s `traceId` extension. Tracing adds real value here (seeing where time goes across a request), but the *correlation* problem is mostly solved.

**The ingestion pipeline has no correlation at all today, and it's structurally why:**
```
IngestionScheduler (@Scheduled, no HTTP request)
  → IngestionService.ingestAllSources()
  → adapter fetch/normalize
  → TransactionEventPublisher.publish() → Kafka: transactions.ingested
  → [async boundary — a different thread, possibly a different process]
  → TransactionIngestedListener consumes
  → RuleBasedCategorizer
  → TransactionPersister
```
Verified directly: `IngestionScheduler` is a `@Scheduled` method — `CorrelationIdFilter` is a Servlet `Filter`, which only ever runs on an HTTP request thread, so it never touches this path; MDC is empty for the entire ingestion cycle. `TransactionIngestedListener` has zero MDC or `eventId` logging today (confirmed by grep) — a log line from the consumer side carries no field connecting it back to the scheduler tick or adapter fetch that produced it. If an on-call engineer is asked "why did this specific transaction end up in `ingestion_error`," today's honest answer is *"grep the timestamp range and hope nothing else was happening at the same time"* — exactly the kind of investigation distributed tracing exists to replace.

## 2. What already exists as a head start

Two things make this cheaper than it looks:

- **The JSON log pipeline already emits every MDC key as a top-level field** (`logback-spring.xml`: *"MDC keys (including correlationId set by CorrelationIdFilter) are emitted as top-level JSON fields"*). Once `traceId`/`spanId` land in MDC — which Micrometer Tracing does automatically once wired — **zero logback changes are needed** for them to show up in every log line. This isn't a new pipeline to build; it's a pipeline that already accepts more fields than it currently receives.
- **`TransactionIngestedEvent` already carries a per-event `eventId` (UUID)**, generated once at publish time. It's not currently used for correlation (not logged, not propagated as a header), but it's a real, already-unique identifier that could serve as an interim/fallback correlation key even before real trace propagation lands — cheaper than nothing while this handoff's larger work is in progress, if a quick win is wanted first.

## 3. The hard part is mostly already solved — it just needs turning on

The instinct here might be "manually thread a trace ID through Kafka headers by hand" — **don't**. Spring Kafka already has built-in Micrometer Observation support for exactly this (verified against the pinned `spring-kafka:4.1.1` jar — `org.springframework.kafka.support.micrometer.KafkaListenerObservation` and the producer-side equivalent both exist in the dependency already on the classpath). When enabled, it:
- Creates a producer-side span when `KafkaTemplate.send()` is called, starting a fresh root trace if none exists (exactly the scheduler-triggered case in §1 — there's no parent HTTP request to inherit from, and the instrumentation handles that correctly on its own).
- Injects the trace context into the Kafka record as W3C `traceparent` headers.
- On the consumer side, extracts those headers and continues the *same trace* as a child span — this is the actual mechanism that solves the async-boundary correlation problem in §1, and it's not custom code, it's turning on something already in the dependency tree.

**The catch**: this app builds its `KafkaTemplate` and listener container factory manually (`KafkaProducerConfig`, `KafkaConsumerConfig`) rather than relying on Boot's autoconfigured ones — so the observation flags need to be set explicitly on the hand-built beans, not assumed from a property:

```java
// KafkaProducerConfig — on the KafkaTemplate bean
KafkaTemplate<String, TransactionIngestedEvent> template = new KafkaTemplate<>(transactionEventProducerFactory);
template.setObservationEnabled(true);
```
```java
// KafkaConsumerConfig — on the ConcurrentKafkaListenerContainerFactory
factory.getContainerProperties().setObservationEnabled(true);
```

This is Integration Engineer's change (both files are already their ownership per `claude.md`), and it's the highest-value single change in this entire handoff — it's what turns "no correlation across the async boundary" into "one trace from scheduler tick to persisted row."

## 4. The HTTP side is close to free

Spring Boot 4's `ObservationRegistry`-based auto-instrumentation covers Spring MVC controllers automatically once the tracing bridge dependency (§5) is present — no code change needed in `TransactionController`/`AdminSourceController`. Boot also automatically bridges the active trace into MDC (`traceId`, `spanId`), which is what makes §2's "zero logback changes" claim true for the HTTP path too.

**Open design question, not decided here**: once a real `traceId` exists, should `CorrelationIdFilter`'s `X-Correlation-ID` (client-facing, already a stable contract — echoed on responses, asserted in `ApiValidationTest`, referenced in `ProblemDetail.traceId`) be (a) left alone and coexist as a separate, simpler client-facing ID, or (b) unified so `correlationId` *is* the OTel trace ID, so a support engineer only ever needs to ask a customer for one number? Backend Engineer's call — flagged here because it touches a contract Testing Engineer and Documentation Engineer both have surface area in (`ApiValidationTest`, `openapi.yaml`'s error response docs).

## 5. Dependencies (none of this exists in `pom.xml` today)

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```
Plus `management.tracing.sampling.probability` in `application.yml` — Boot's own default is **0.10** (10% of traces sampled), tuned for high-traffic production systems where 100% sampling would be expensive. At this project's actual ingestion volume (one scheduler tick per 60s, a handful of transactions per cycle against the mock adapters), 10% sampling means **most ingestion cycles would produce no trace at all**, defeating the point of building this for debuggability. Recommend `1.0` (100%) for now, explicitly revisited if/when real source volume via `docs/observability-alerting-handoff.md`-shaped load makes that too expensive — this is a one-line config value, easy to change later, not worth debating at length now.

## 6. Backend/collector choice — needs an ADR, not just a docker-compose line

This is genuinely new infrastructure, which is exactly the category Architect's own `claude.md` Key Focus now requires checking against the requirements guide's "What Not to Build" list before adding (added 2026-09-24, after the Helm/Kubernetes scope-creep finding — see `docs/retrospectives/LESSONS.md`). **Do not add a tracing backend container without writing that ADR first**, the same discipline that was missed once already on this project.

That said, the natural candidate is worth naming as a starting point for that ADR, not a decision made here: **Grafana Tempo**. Grafana already runs in this stack (`docker-compose.yml`) and already has a working Prometheus datasource pattern (`grafana/provisioning/datasources/`) — Tempo is Grafana's own tracing backend, and adding it follows the exact same "one more datasource in Grafana" shape as the existing setup rather than introducing an unrelated new tool (Jaeger, Zipkin) with its own separate UI. The counter-argument the ADR should genuinely weigh: is a dedicated tracing backend justified at this project's real scale at all, or does structured JSON logging with `traceId`/`spanId` fields (§2 — already free once this lands) cover the actual debugging need without a new stateful service to operate? That question is exactly what ADR-010 ("No Redis initially") already asked and answered for caching; this deserves the same rigor, not a reflexive "add the obvious tool."

## 7. What this does *not* replace

- **The freshness/completeness API metadata** (ADR 002/008) stays the source of truth for "is this data current/complete" — tracing shows *why* an individual request or ingestion cycle was slow or failed, not the aggregate data-quality signal that metadata already provides.
- **The reconciliation job** (`docs/reconciliation-alerting-handoff.md`) — tracing helps root-cause *how* a specific record went wrong once reconciliation has already told you *that* something did. They're complementary, not overlapping.
- **Historical data.** Tracing only covers requests/ingestion cycles that happen after this is deployed — it does not retroactively explain anything in past logs, which is exactly why the `eventId`-as-interim-key idea in §2 has some standalone value even before this full handoff is built.

## 8. Acceptance criteria for this handoff

- [ ] Integration Engineer has applied §3's two `setObservationEnabled(true)` calls — this is the one change that matters most; everything else in this document is secondary to it.
- [ ] Backend Engineer has decided §4's `correlationId`-vs-`traceId` question and updated `openapi.yaml`/`ApiValidationTest` if the contract changes.
- [ ] Architect has written the backend-choice ADR from §6 *before* SRE stands up any new container — Tempo or otherwise, and including the "do we need this at all yet" question ADR-010 already modeled.
- [ ] SRE has deployed the chosen backend (if the ADR concludes one is justified) and added it as a Grafana datasource, following the existing Prometheus datasource pattern.
- [ ] A trace has been manually verified end-to-end at least once: one Kafka-published event's producer span and its consumer-side child span appear as *one trace*, not two disconnected ones — this is the concrete proof that §3 actually worked, not just that the dependency compiled.
