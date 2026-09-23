# ADR 003: Transaction Identity Strategy

## Status
Accepted

## Context
We are aggregating transactions from multiple sources. Source-provided IDs are not globally unique. For example, Source A and Source B might both provide a transaction with ID "123", but they represent different financial events. Ingestion is at-least-once, so retries and replays will re-present rows we have already stored.

## Decision
The system will use a composite natural key — `(source_id, source_transaction_id)` — for uniqueness, plus a generated UUID for internal referencing.

## Alternatives Considered
- **Source-provided ID alone as primary key**: rejected — collides across sources ("123" from Source A ≠ "123" from Source B) and would either overwrite foreign rows or force silent disambiguation.
- **Internal UUID as the only uniqueness concept**: rejected — a generated UUID cannot detect that an incoming record is a replay of one already stored; idempotency requires a natural-key constraint the data actually carries.
- **Hash of the full payload as identity**: rejected — cosmetic edits by a source (timestamp formatting, field order, added whitespace) would change the hash and create false "new" transactions, while genuine updates could be missed.
- **Global deterministic ID minted upstream (source supplies a canonical UUID)**: rejected — not available from the mock sources and cannot be assumed of future sources; would also couple our identity model to upstream behaviour we do not control.
- **Cross-source deduplication as part of identity**: rejected — without a reliable correlation key this "invents certainty" in financial records (see guide §11); out of scope.

## Rationale
1. **Uniqueness**: The combination `(source_id, source_transaction_id)` is guaranteed to be unique per source.
2. **Integrity**: A database `UNIQUE` constraint on this pair prevents accidental duplicate ingestion during retries — and doubles as the idempotency mechanism (ADR 005).
3. **Internal Stability**: A generated internal UUID (`id`) lets us reference transactions consistently and gives foreign keys a standard format, independent of source ID formats.

## Implementation
- Database table: `transactions`
- Constraint: `UNIQUE (source_id, source_transaction_id)` (unique index `idx_unique_source_transaction`)
- Internal ID: `id UUID PRIMARY KEY`
- Note: the column is `source_id` (not `provider_id`) — it is the source system identifier (`SOURCE_A`, …), consistent with the canonical model and API provenance (`source.provider`).

## Consequences
- Every write path must populate both components; adapters are responsible for setting `sourceId` during normalization.
- The consumer relies on constraint violation (insert-or-ignore semantics) rather than check-then-insert, avoiding race conditions under concurrent consumers.
- Identity survives source-side ID reuse across sources, but a source reusing an ID *within itself* for a different transaction would be treated as a duplicate — sources must guarantee intra-source uniqueness (documented assumption).
