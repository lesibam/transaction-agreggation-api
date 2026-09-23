# ADR 010: No Redis (or External Cache) Initially

## Status
Accepted

## Context
Read APIs that aggregate and filter transaction data are natural cache candidates. Redis (or an in-process cache) could sit in front of PostgreSQL to reduce query latency and database load. The temptation grows whenever list endpoints appear slow. But caching introduces a *second* source of truth, and this domain has properties that make naive caching actively dangerous: money must be correct, and freshness/completeness are already first-class API concepts.

## Decision
**No Redis and no external cache layer in the current revision.** All reads are served query-time from PostgreSQL using indexed, keyset-paginated queries and SQL-side aggregates. Freshness is expressed through data (sync state) — not through cache TTLs.

## Alternatives Considered
- **Redis in front of list/summary endpoints**: rejected — cache invalidation for customer-scoped, filter-heavy query shapes is complex and error-prone (key explosion across filter combinations); a stale cache would silently contradict or duplicate the explicit `freshness`/`completeness` metadata we already expose; adds a stateful dependency to the critical read path for gains not yet demonstrated.
- **In-process Caffeine cache**: simpler than Redis and same-process — rejected for now — same invalidation problems across multiple monolith replicas (each replica would diverge), plus memory pressure; revisit only for genuinely static reference data (e.g., category dictionaries), not transactional rows.
- **Materialized/summary tables as a cache substitute**: this is the *real* evolution path (ADR guide §40–41) — rejected *until volume justifies it*, because query-time SQL with proper indexes is the honest baseline; premature pre-aggregation creates staleness bugs that fight ADR 008.
- **HTTP response caching (CDN/browser, `ETag`/`Cache-Control`)**: not needed for authenticated, customer-scoped responses; would risk caching across tenants if misconfigured.
- **Spring's built-in cache abstraction with a pluggable store**: the annotation layer is harmless, but enabling it without a concrete store and an invalidation strategy is cargo-culting — no cache abstraction is wired today.

## Rationale
1. **Correctness over speed**: financial reads must reflect committed state; every cache layer is a place where they might not.
2. **Freshness is already the product**: we tell consumers how stale data is via sync-state metadata; a cache would introduce a *second*, invisible staleness dimension — confusing and harder to reason about.
3. **YAGNI at current scale**: keyset pagination (`transaction_date DESC, id DESC`), indexed foreign keys, and SQL aggregates are designed to perform adequately at assessment scale; no measurement has shown a cache is needed.
4. **Operational simplicity**: fewer stateful systems = fewer failure modes (see ADR 001's spirit).

## Consequences
**Positive**
- Single source of truth for every read; no invalidation bugs, no stampede coordination across replicas.
- The API's freshness semantics stay unambiguous — staleness only ever comes from ingestion lag, which we measure and expose.
- Local/dev/test topology stays simple (Postgres + Kafka only).

**Negative**
- Hot read patterns will hit PostgreSQL directly; if p99 or connection-pool saturation becomes a problem, we must add indexes/sharding/materialized aggregates — a real, measured evolution step rather than a speculative cache.
- Repeated identical queries re-execute SQL every time — acceptable at current volumes; explicitly re-open ADR 010 if load testing (K6, Future Evolution) shows read-bound bottlenecks.
- Anyone adding a cache later **must** route invalidation through ingestion events or sync-state transitions — otherwise they will violate ADR 008's guarantee. This constraint is recorded here so the future implementer inherits it.
