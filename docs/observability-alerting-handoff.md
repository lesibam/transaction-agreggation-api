# Handoff: Observability Alerting

| | |
|---|---|
| **From** | Architect, on behalf of the SLI-owning agents (Backend Engineer, Integration Engineer, Domain Expert, DBA — each named per signal below) |
| **To** | Operations/SRE Engineer |
| **Status** | **Proposed.** Closes out `IMPLEMENTATION_PLAN.md` Phase 6's "Alerting Strategy" and "SLI/SLO Definition" items, which are open today — metrics exist, but *nothing evaluates them against a threshold*. |
| **Purpose** | Same pattern as `docs/reconciliation-alerting-handoff.md`: the agent who understands what "healthy" means for a signal defines the SLI and threshold; SRE owns turning it into a firing alert. Unlike the reconciliation handoff, most of this system's SLIs already exist as metrics today — this document is mostly about *evaluating* what's already emitted, plus naming the few real instrumentation gaps standing in the way of the rest. |

---

## 1. Where this sits relative to what already exists

Three things are already built and should **not** be re-litigated here:

- **Metrics emission** — `transact.ingestion.duration`, `.records.received/.published/.quarantined/.duplicates`, `.source.sync.success/.failure` (all tagged `source`), plus Boot's default `http.server.requests.*`, `jvm.*`, `hikaricp.*` (`IngestionService.java:69,88,106,110,117,154`; see README "Observability").
- **A dashboard** — `grafana/dashboards/transact-overview.json`, auto-provisioned with `./dev.sh start`. It visualizes these metrics; it does **not** alert on them (its own description panel says so explicitly).
- **The SLO targets** — already numbers, not vague aspirations, written in `IMPLEMENTATION_PLAN.md` Phase 6:
  - Availability: error rate < 0.1% for critical endpoints
  - Latency: p99 response time < 200ms for transaction queries
  - Freshness: 95% of sources synced within the last 15 minutes

What's missing is the middle layer: turning "a metric exists" and "a target is written down" into an actual alert rule. That's this handoff.

**Routing decision is not re-opened here.** `docs/reconciliation-alerting-handoff.md` §5 already raises Alertmanager-vs-Grafana-unified-alerting as an open question for SRE; whichever SRE picks applies to both handoffs identically. Building two separate alerting mechanisms for the same Grafana/Prometheus stack would be its own bug.

## 2. SLI-by-SLI: what can be alerted on today vs. what's blocked on instrumentation

| SLI | Owner | Target (Phase 6) | Can alert today? | Notes |
|---|---|---|---|---|
| API error rate | Backend Engineer | < 0.1% on critical endpoints | **Yes** | Boot's default `http_server_requests_seconds_count` already carries a `status`/`outcome` tag per request — no new instrumentation needed, contrary to what Phase 6's "per-endpoint error counters... NOT yet instrumented" note implies. See §3.1. |
| API latency (p99) | Backend Engineer | p99 < 200ms | **No — blocked** | `http_server_requests_seconds` is a Timer with no percentile histogram configured. Today only avg (`sum`/`count`) is derivable, which cannot honestly stand in for a p99 SLO. See §3.2 for the one-line fix. |
| Source freshness | Domain Expert | 95% of sources synced within 15 min | **No — blocked** | Freshness is computed at API request time (`TransactionQueryService`), not exported as a standing metric. An idle system with no API traffic currently has no way to alert on a source going stale. See §3.3. |
| Ingestion failure rate | Integration Engineer | *(not numerically targeted in Phase 6 — proposed here)* | **Yes** | `transact.ingestion.source.sync.failure` already exists per source. See §3.4. |
| Kafka consumer lag | Integration Engineer | *(Phase 6: "NOT yet instrumented")* | **No — blocked** | Confirmed: `KafkaConsumerConfig` builds its `ConsumerFactory` manually and never registers a Micrometer listener. See §3.5 for the fix. |
| DLQ depth (poison messages) | Integration Engineer | *(not yet targeted — proposed here)* | **No — blocked** | Nothing currently counts messages landing on `transactions.dlq`; depends on the same fix as consumer lag. See §3.5. |
| DB pool saturation | DBA | *(not yet targeted — proposed here)* | **Yes** | `hikaricp_connections_active`/`hikaricp_connections_max` already exported by default. See §3.6. |

Three of seven SLIs are alertable with zero new code. Two are one config line or one method call away. Two (freshness, DLQ depth) need a small amount of new code. None need a new dependency.

## 3. Per-SLI detail

### 3.1 API error rate — ready today
```promql
sum(rate(http_server_requests_seconds_count{uri=~"/v1/.*", status=~"5.."}[5m]))
/ sum(rate(http_server_requests_seconds_count{uri=~"/v1/.*"}[5m]))
> 0.001
```
**Proposed tiers**: Warning at sustained >0.1% over 15 min; Critical at >1% over 5 min (a sharp spike is worth waking someone even before the sustained window closes — unlike reconciliation drift, a live 5xx spike is actively hurting users right now, not a lagging data-correctness signal, so it doesn't need the same persistence discipline). 4xx is deliberately excluded from this SLI — a wave of 401s is a client bug or an attack, not an availability problem; see §3.7 if you want that as a separate, security-relevant signal.

### 3.2 API latency p99 — one config line away
Add to `application.yml`:
```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
```
This makes Micrometer publish `http_server_requests_seconds_bucket` (a real histogram), from which `histogram_quantile(0.99, ...)` produces an honest p99 — not an approximation. Without this line, do not write a p99 alert rule against `_max` or `sum/count`; both misrepresent the actual SLO. This is Backend Engineer's config to own (it's an `application.yml` change, not an SRE-owned file), but it's listed here because SRE is the one who'll notice a p99 alert rule silently producing garbage without it.

### 3.3 Source freshness — needs a small new metric
Freshness today is request-triggered (`TransactionQueryService.buildMeta` → per-source age vs. `app.freshness.fresh-seconds`/`stale-seconds`). Propose Domain Expert add a periodic Gauge, computed on the same cadence as the ingestion scheduler (or its own lightweight tick), independent of API traffic:
```
transact.source.freshness.seconds{source=...}  — seconds since last_successful_sync
```
Alert: `transact_source_freshness_seconds > 900` (15 min, matching the Phase 6 target) sustained for 2 consecutive scrapes (avoid a single scrape landing mid-cycle). This reuses the exact threshold already chosen in Phase 6 rather than inventing a new one — the number isn't new work, only the metric that lets Prometheus see it without a request happening to ask.

### 3.4 Ingestion failure rate — ready today
```promql
increase(transact_ingestion_source_sync_failure_total[15m]) > 0
```
**Proposed**: Warning on any failure; Critical if the same source has failed 3 consecutive scheduler cycles (`increase(...[3m]) >= 3` at the default 60s ingestion interval) — mirrors the persistence-over-single-reading principle from the reconciliation handoff, because a single transient failure is exactly what the existing bounded-retry-with-backoff in `IngestionService` is designed to absorb; only *sustained* failure is actionable by a human.

### 3.5 Kafka consumer lag & DLQ depth — same underlying fix
`KafkaConsumerConfig.transactionIngestedContainerFactory` builds `DefaultKafkaConsumerFactory` directly and never registers a metrics listener. Verified against the pinned `spring-kafka:4.1.1`: `org.springframework.kafka.core.MicrometerConsumerListener` exists and is the standard mechanism for exactly this. Proposed change (Integration Engineer's file, per `claude.md` ownership):
```java
DefaultKafkaConsumerFactory<String, TransactionIngestedEvent> consumerFactory =
    new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
        new JsonDeserializer<>(TransactionIngestedEvent.class, objectMapper));
consumerFactory.addListener(new MicrometerConsumerListener<>(meterRegistry));
```
This exposes Kafka's native client-side metrics (including per-partition lag) under Micrometer, from which both consumer-lag and DLQ-topic-depth alerts become straightforward — no separate DLQ counter needs hand-writing once the DLQ topic's own consumer-side metrics are visible. **This one change unblocks two of the seven SLIs in §2's table**, so if SRE only asks Integration Engineer for one thing from this handoff, this is it.

### 3.6 DB pool saturation — ready today
```promql
hikaricp_connections_active / hikaricp_connections_max > 0.8
```
Warning at >80% sustained 10 min, Critical at >95% sustained 5 min. No new instrumentation; this is purely an SRE alert-rule-authoring task.

### 3.7 Optional: auth failure rate (Security Engineer's domain, not in Phase 6's three targets)
Not one of the three Phase 6 SLOs, flagged here because it's cheap and the data already exists: a spike in `http_server_requests_seconds_count{status="401"}` or `{status="403"}` on `/v1/**` can be an early signal of credential stuffing or a misconfigured client — different response than a 5xx spike (§3.1), so keep it a separate rule, not folded into the error-rate SLI. Security Engineer's call whether this is worth wiring now or deferred; noted here so it isn't lost.

## 4. Runbook shape (draft — SRE to refine)

Distinguish two response postures, matching §3.1's reasoning:
- **Live-impact alerts** (error rate, latency, DB saturation) — page immediately, sustained-window tiers exist only to filter noise, not to delay response once confirmed real.
- **Lagging-signal alerts** (freshness, ingestion failure, consumer lag, DLQ depth) — a source or the pipeline has been degraded for a while by the time these fire; investigate during the next business window unless the persistence window itself has grown severe (Critical tier), same posture as `docs/reconciliation-alerting-handoff.md` §6.

For every alert: link back to the Grafana panel showing the specific `source`/`uri` tag that fired, not just "something is wrong" — every proposed rule above carries the tag needed to do that.

## 5. Dashboard ask

Extend `grafana/dashboards/transact-overview.json` (or add panels — SRE's call) with: the p99 latency panel (once §3.2 lands), source freshness per source (once §3.3 lands), Kafka consumer lag and DLQ depth (once §3.5 lands). The existing panels already cover ingestion outcomes, sync success/failure, HTTP status/latency (avg only, pending §3.2), and DB pool — no need to duplicate those.

## 6. Acceptance criteria for this handoff

- [ ] Backend Engineer has applied §3.2's config line, or explicitly declined and documented why (e.g., cardinality/storage cost concerns worth naming if so).
- [ ] Integration Engineer has applied §3.5's `MicrometerConsumerListener` change, or explicitly declined.
- [ ] Domain Expert has scoped §3.3's freshness gauge as a small, separate piece of work (it's new code, not config — shouldn't block the four zero-new-code alerts in §2 from shipping first).
- [ ] SRE has written and deployed alert rules for the three "ready today" SLIs (§3.1, §3.4, §3.6) using whichever mechanism was chosen per the reconciliation handoff's §5 — these don't need to wait on anything above.
- [ ] SRE has decided the posture split in §4 (live-impact vs. lagging-signal) applies as written, or proposed an alternative.
