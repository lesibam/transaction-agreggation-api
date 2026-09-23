# ADR 006: Adapter-Based Source Integration

## Status
Accepted

## Context
The three sources expose heterogeneous payload shapes, field names, and conventions (`amount` vs `value`, signed vs unsigned with direction flags, different date formats). Their source-specific transaction IDs are only meaningful in context. Today the sources are **in-process mocks**; real HTTP integrations are deferred. The rest of the system must not care which shape a source happens to use, and adding Source D later must not require touching the pipeline.

## Decision
Each source is integrated through its own **adapter** implementing the `TransactionSource` port (`getSourceId()`, `fetchTransactions(cursor)`), and **normalization happens inside the adapter** — raw source DTOs (`SourceADto`, `SourceBDto`, `SourceCDto`) are converted to `CanonicalTransaction` before anything leaves the integration layer. Mock data generation lives in the adapter, standing in for a future HTTP client without changing any downstream contract.

## Alternatives Considered
- **Shared normalization engine fed by raw payloads** (pipeline receives raw JSON/XML and a format descriptor): rejected — pushes source-specific knowledge into a shared component, recreating the coupling the adapter pattern exists to prevent; every source change would risk breaking the shared engine.
- **Direct source-to-database mapping** (each source writes its own table shape; union views at query time): rejected — pushes heterogeneity into the query layer, multiplies query paths, and makes canonical invariants (identity, money, direction) unenforceable in one place.
- **One generic adapter with format switch/if-else inside** (single class handling all sources): rejected — conditional sprawl, violates single-responsibility, and makes per-source failure isolation and testing harder.
- **anti-corruption layer as a separate deployed service**: rejected — overkill for a modular monolith; the adapter *is* the ACL, in-process (see ADR 001).
- **Buying/wrapping a commercial ETL/CDC tool**: rejected — no requirement justifies the dependency; sources are not databases to be captured.

## Rationale
1. **Encapsulation of chaos**: format quirks, field renames, and sign conventions die at the adapter boundary; everything downstream sees only `CanonicalTransaction`.
2. **Testability**: each adapter can be unit-tested against its own fixture payloads; the pipeline can be tested with canned canonical rows.
3. **Replaceable network layer**: when real HTTP arrives, only the adapter's fetch internals change (WebClient + timeouts + retries) — port signature and normalization contract stay fixed.
4. **Incremental sync**: the cursor for `source_sync_state` is owned per source, threaded through the same port.

## Implementation
- `TransactionSource` port in `infrastructure.integration`.
- `SourceAAdapter`, `SourceBAdapter`, `SourceCAdapter` in `infrastructure.integration.adapter` with per-source DTOs in `infrastructure.integration.dto`.
- Normalization sets `sourceId`, `sourceTransactionId`, `direction`, absolute `amount`, `normalizationVersion`, and `ingestedAt`.
- Scheduler iterates all registered `TransactionSource` beans; one source's failure does not abort the others.

## Consequences
- Adding a source means: new adapter + DTO + registration as a Spring bean + seed account; no changes to consumer, categorizer, or query paths.
- Mock and real implementations share the port — feature flags or profile-based beans can swap them later without pipeline changes.
- Normalization logic versioning (`normalizationVersion` on each row) becomes the adapter's responsibility, enabling scoped re-normalization when formats evolve.
- Because mocks are in-process, current "source outage" behaviour is exercised via tests and failure injection rather than real network faults — real resilience semantics (timeouts, bulkheads) arrive with the HTTP clients (Future Evolution).
