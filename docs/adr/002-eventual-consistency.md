# ADR 002: Eventual Consistency & Freshness Metadata

## Status
Accepted

## Context
Aggregation of data from multiple external sources introduces a problem: sources may be updated at different times, or some may be unavailable. Providing a "real-time" global snapshot is technically impossible without locking external sources — and external sources cannot be locked by us. Consumers still need to know whether the data they are reading is trustworthy *right now*.

## Decision
The system will adopt an **Eventual Consistency** model. Each source's last successful ingestion time is tracked in `source_sync_state`; API responses expose explicit freshness and completeness metadata rather than implying real-time accuracy.

## Alternatives Considered
- **Synchronous runtime aggregation** (call all sources on every API request): rejected — response latency becomes bound by the slowest source, availability collapses when any source is down, and the API would depend on third-party SLAs for every read.
- **Distributed transaction coordination (2PC across sources)**: rejected — sources are independent systems that do not participate in a shared transaction manager; this is not achievable with uncooperative third parties.
- **Strong consistency via continuous sync into a lock-step store**: rejected — would still lag real source state, adds continuous write load, and does not eliminate the fundamental lag; it only hides it.
- **Returning cached data with no metadata** (stale-while-unaware): rejected — silently serving stale or incomplete data is worse than admitting it; it violates the core requirement that partial results are never presented as complete.

## Rationale
1. **Availability**: The API must remain available even if one or more sources are down.
2. **Performance**: We cannot let the slowest source dictate the response time of every API request.
3. **Transparency**: Instead of pretending the data is real-time, we explicitly communicate the freshness of the data to the consumer.

## Implementation
- Responses include a `meta` block with `freshness` (per-source `lastSync` and status) and a `completeness` flag (`COMPLETE` vs `PARTIAL`).
- Completeness is derived from `source_sync_state` (all sources `SUCCESS` ⇒ `COMPLETE`, otherwise `PARTIAL`).
- Freshness categories (`FRESH`, `STALE`, `VERY_STALE`) derive from configurable thresholds (defaults 5 / 30 minutes).

## Consequences
**Positive**
- Source outages do not make the API unavailable; reads are always served from PostgreSQL.
- Predictable API latency independent of external sources.
- Per-source ingestion can be retried independently.

**Negative**
- Newly ingested transactions may not appear immediately (bounded by the scheduler interval).
- Consumers must understand freshness/completeness semantics — mitigated by documenting them in the API contract and `openapi.yaml`.
