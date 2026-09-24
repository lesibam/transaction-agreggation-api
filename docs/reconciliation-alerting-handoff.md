# Handoff: Reconciliation Alert Thresholds

| | |
|---|---|
| **From** | Data Reconciliation Engineer |
| **To** | Operations/SRE Engineer |
| **Status** | **Proposed — blocked on the reconciliation job itself.** `IMPLEMENTATION_PLAN.md` Phase 9 ("Source Reconciliation") tracks that job as not yet built; today "reconciliation" is one sentence in ADR 004's prose, not running code. |
| **Purpose** | Let the two agents build in parallel: this document is the contract between them. Data Reconciliation Engineer builds the job to emit exactly these metrics; SRE builds the alert rules and dashboard panels against exactly these metrics and thresholds. Neither side should have to wait on the other to start. |

Per `claude.md`: Data Reconciliation Engineer defines *what* to alert on and *at what threshold*; Operations/SRE Engineer owns *where it actually fires* (Alertmanager/Grafana alerting config, on-call routing, the dashboard it surfaces on). This document is the join point.

---

## 1. What "reconciliation" means here

The reconciliation job periodically compares what this system has ingested against what each source says it sent, for the same time window, and reports the difference. It answers one question: **does what we hold actually match the source of truth, right now** — not "did ingestion run" (that's `source_sync_state`, already built) and not "is the data fresh" (that's the freshness model in ADR 002/008, already built). A source can be `SUCCESS` and `FRESH` and still be silently wrong — reconciliation is the only thing designed to catch that.

Two independent kinds of drift matter, and they mean different things:

- **Count drift** — we hold a different *number* of records for a source+window than the source reports. Usually means missing records (a quarantine that should have matched, a bug in the adapter) or duplicates that slipped past the idempotency constraint (which shouldn't be possible given `UNIQUE(source_id, source_transaction_id)`, but reconciliation is exactly the mechanism that would catch it if that guarantee were ever violated).
- **Amount drift** — the *count* matches but the *sum* doesn't. Worse signal than count drift: it means an individual transaction's amount was recorded wrong, not that a record is missing — a normalization bug or precision loss, not a delivery problem.

Both are per-source, per-currency, per-window. They are computed independently and must not be collapsed into one "reconciliation OK/not OK" boolean — an SRE paged for "reconciliation failed" with no indication of which kind needs to know immediately whether to look at the adapter's field mapping (amount drift) or at delivery/quarantine logs (count drift).

## 2. Metrics contract

The reconciliation job (once built) emits these via the same `MeterRegistry` pattern `IngestionService` already uses (`transact.ingestion.*` — see `IngestionService.java:69,88,106,110,117,154`). Proposed names, following that exact convention:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `transact.reconciliation.run.duration` | Timer | `source` | How long one reconciliation pass took for a source |
| `transact.reconciliation.run.success` | Counter | `source` | The reconciliation pass itself completed (could still find drift) |
| `transact.reconciliation.run.failure` | Counter | `source` | The pass itself couldn't complete — e.g. the source's reporting endpoint was unreachable. **This is not the same as finding drift** and should alert differently (see §4). |
| `transact.reconciliation.drift.count` | Gauge | `source` | Absolute record-count difference, current window (signed: positive = we have more than the source reports, negative = fewer) |
| `transact.reconciliation.drift.count.ratio` | Gauge | `source` | Count drift as a fraction of the source's reported count — normalizes across sources of very different volume |
| `transact.reconciliation.drift.amount` | Gauge | `source`, `currency` | Absolute monetary sum difference, current window |
| `transact.reconciliation.windows.consecutive_drift` | Gauge | `source` | How many consecutive reconciliation runs this source has shown *any* nonzero drift — the persistence signal §3 depends on |

`consecutive_drift` resets to 0 the moment a run shows zero drift for that source. This is the field that turns "one noisy run" into "an actual incident" — see §3.

## 3. Why thresholds must require persistence, not a single reading

**Do not alert on a single reconciliation run showing drift.** A source can legitimately be mid-sync when reconciliation runs — its own reporting lag means a single window can show a transient mismatch that resolves itself on the next successful ingestion cycle, the same way `FreshnessStatus` already tolerates lag up to `app.freshness.stale-seconds` before treating something as a problem (`application.yml`, `TransactionQueryService.java:68-69`). Reconciliation alerting needs the equivalent discipline, or the first thing that happens in production is the on-call engineer muting this alert after the third false page in a week — after which it protects nothing.

**Alert on persistence** (`transact.reconciliation.windows.consecutive_drift`), not on any single value of `drift.count`/`drift.amount`. Proposed reconciliation cadence: **hourly** (`app.reconciliation.interval-ms`, default `3600000`), deliberately decoupled from the 60-second ingestion scheduler — reconciling against a source's own reporting API is a heavier, less time-sensitive operation than ingestion itself.

## 4. Proposed thresholds (initial — tune after real data exists)

These are starting points, not measured values — there is no running job yet to measure against. Flagged explicitly as provisional; SRE and Data Reconciliation Engineer should revisit after the job has run against real source volume for a couple of weeks, the same way `app.freshness.fresh-seconds`/`stale-seconds` are configurable rather than hardcoded.

| Condition | Tier | Suggested rule |
|---|---|---|
| Reconciliation run itself fails (`run.failure`) | **Warning** | 1 failure = warning; 3 consecutive failures for one source = critical (the job can't tell if data is right at all, which is its own incident) |
| Count drift, any nonzero value, single run | *(no alert)* | Expected noise from source-side lag — this is what `consecutive_drift` exists to filter out |
| Count drift ratio > 0.5% **or** absolute drift > 10 records, sustained 2 consecutive runs (`consecutive_drift >= 2`) | **Warning** | Notify (Slack/ticket), not a page — investigate during business hours |
| Count drift ratio > 2% **or** `consecutive_drift >= 3` (~3 hours unresolved) | **Critical** | Page on-call |
| Any amount drift > 0, sustained 2 consecutive runs | **Critical** | Skip the warning tier entirely — amount drift with a matching count means data corruption, not a delivery timing issue, and shouldn't wait for a "sustained" grace period the way count drift does. One confirmed occurrence (not a single-run blip, but the second consecutive one) is enough to page. |

Rationale for treating amount drift more aggressively than count drift: a missing record (count drift) is bounded and self-evident once found — you know exactly what's missing. A wrong amount on a record that's otherwise present is silent — nothing else in this system's current design (idempotency, quarantine, freshness) would ever catch it. It gets the least tolerance because it has the fewest other safety nets.

## 5. Routing (open question for SRE)

No Alertmanager exists in `docker-compose.yml` today — only Prometheus and Grafana. Two paths, SRE's call per `claude.md` ownership of `prometheus/`, `grafana/`:

1. Add an Alertmanager service + `prometheus/alert.rules.yml`, route via its own notification config.
2. Use Grafana's built-in unified alerting (Grafana 11, already in the stack) directly on the metrics above — one less moving part for a stack this size.

This handoff doesn't prescribe which; it only fixes the metric names/tags and thresholds both approaches would consume identically. Whichever is chosen, **there is no real on-call rotation on this project today** — routing should be built pointing at *a* channel (e.g., a Slack webhook), not assumed to reach a person. Don't wire PagerDuty/Opsgenie-shaped assumptions into the alert rule itself; keep the receiver pluggable.

## 6. First-response runbook (draft — SRE to refine once real alerts exist)

1. Check `transact.reconciliation.run.failure` first — if the run itself is failing, this isn't a data problem yet, it's a connectivity/config problem with the reconciliation job's access to the source's reporting endpoint.
2. Pull up the reconciliation dashboard (§7) and identify: which source, count or amount drift, and the trend on `consecutive_drift` (growing, flat, or already recovering).
3. **Count drift** → check `ingestion_error` for that source/window (was it quarantined? `NO_ACCOUNT`/`AMBIGUOUS_ACCOUNT`/`VALIDATION`?) before assuming data loss. Cross-reference `source_sync_state.status` — was the source `FAILED` during this window (expected, already-alerted gap) or `SUCCESS` (unexplained gap, the real incident)?
4. **Amount drift** → this is not an ingestion-pipeline question, it's a normalization-correctness question. Pull a sample of the specific drifted records and compare the adapter's parsed amount against the source's raw payload for the same `source_transaction_id`.
5. Escalate to Integration Engineer (adapter-level normalization bugs) or Data Reconciliation Engineer (reconciliation job's own comparison logic — it can be the thing that's wrong, not the data) as appropriate once root cause is narrowed.

## 7. Dashboard ask

Add a "Reconciliation" section to `grafana/dashboards/transact-overview.json` (or a new dashboard file, SRE's call) once the job exists, covering at minimum: `drift.count.ratio` and `drift.amount` per source over time, `consecutive_drift` per source (the persistence signal directly), and `run.success`/`run.failure` rate. Data Reconciliation Engineer will provide the PromQL once the metrics are live and real field names are confirmed against an actual `/actuator/prometheus` scrape — the queries in this handoff are intentionally not pre-written, to avoid repeating the mistake flagged elsewhere in this repo of writing dashboard queries before verifying real exported metric names.

## 8. Acceptance criteria for this handoff

- [ ] SRE has reviewed §4's thresholds and either accepted them or proposed specific alternatives (not just "seems fine" — this is a financial-correctness alert, worth a real second opinion).
- [ ] SRE has decided Alertmanager vs. Grafana unified alerting (§5) and documented the choice (an ADR if it's a meaningful enough call, otherwise a note in `docs/03-architecture.md`).
- [ ] Metric names in §2 are treated as fixed once Data Reconciliation Engineer starts implementing — changing them after alert rules are written means redoing both sides' work.
