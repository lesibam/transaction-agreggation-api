# ADR 008: Partial Results and Source Freshness

## Status
Accepted

## Context
The system ingests from three independent sources on a schedule. Any source can be slow, down, or returning garbage at any moment. A read API that blocks on all sources fails open availability; an API that returns whatever is in the database without qualification lies about completeness. Consumers making financial decisions need to know *both* what data exists and how much to trust it — without the API inventing certainty.

## Decision
Reads are always served from the local store (eventual consistency, ADR 002), and **every list/summary response carries explicit trust metadata**:

1. **`completeness`**: `COMPLETE` only when every configured source's last cycle in `source_sync_state` is `SUCCESS`; otherwise `PARTIAL`. Derived from sync state — never inferred from row counts.
2. **`freshness`**: per-source `lastSync` timestamp plus a status bucketed by configurable age thresholds (defaults: ≤ 5 min `FRESH`, ≤ 30 min `STALE`, else `VERY_STALE`; `NEVER_SYNCED`/`UNKNOWN` when no success is recorded). Overall status is the worst of the per-source statuses.
3. **Per-source detail** on both the transactions and summary responses, so a `PARTIAL` result names *which* source is lagging — not just that something is.
4. Sync failures mark `source_sync_state.status = FAILED` (with `failure_count`, `last_error`), exposed via `/v1/admin/sources` for operators.

Partial results are **returned, labelled** — never suppressed, never dressed up as complete.

## Alternatives Considered
- **Fail everything when any source is down**: rejected — one flaky source would make the entire API unavailable; availability of the other sources' data is worth more than uniform failure, *provided* the gap is disclosed.
- **Return data with no metadata** (hope nobody notices): rejected — the cardinal sin this design exists to prevent; consumers cannot distinguish a quiet day from a broken pipeline.
- **Synchronous fetch-on-read to "fill the gap" before responding**: rejected — reverts to worst-source-bound latency and couples read availability to third parties (see ADR 002).
- **Boolean `stale` flag only** (single bit, no per-source detail): insufficient — operators and consumers cannot act on "something is wrong" without knowing which source and since when.
- **Materialized freshness cache with TTL**: rejected for now — freshness must reflect actual sync events, not cache expiry; adds a component without improving truthfulness.

## Consequences
**Positive**
- Consumers can gate their own behaviour on `completeness`/`freshness` (e.g., hide "monthly spend" widgets when `PARTIAL`).
- Operations get a precise signal: which source, since when, how many consecutive failures.
- API availability is decoupled from source availability.

**Negative**
- Every response carries a larger `meta` block; clients must actually read it (documented loudly in the API contract and `openapi.yaml`).
- Threshold tuning becomes a real operational concern — defaults (5/30 min) are configurable and must be reviewed against the actual ingestion interval.
- A source stuck `FAILED` still yields `200 PARTIAL` responses forever; alerting on `source_sync_state` is required for someone to notice (alerting is Future Evolution — today `/actuator/health` and `/v1/admin/sources` are the manual signals).
