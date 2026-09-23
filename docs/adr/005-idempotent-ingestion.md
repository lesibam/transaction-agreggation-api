# ADR 005: Idempotent Ingestion

## Status
Accepted

## Context
Ingestion runs on a schedule against sources we do not control, over a messaging layer with **at-least-once** delivery. Fetches can time out after the source has already done work; Kafka can redeliver events after a consumer crash; schedulers can be re-triggered. Any of these can present the same transaction more than once. Financial records must not double-count.

## Decision
Ingestion is idempotent, enforced **in the database**, not in application pre-checks:

1. `UNIQUE (source_id, source_transaction_id)` on `transactions` is the single source of duplicate detection.
2. The Kafka consumer persists with insert-or-ignore semantics — a `DataIntegrityViolationException` on the unique index means "already ingested" and is counted as a skip, not an error.
3. Duplicate handling never uses check-then-insert (`exists → insert`), which races under concurrency.
4. Sync bookkeeping (`source_sync_state.cursor`, `last_successful_sync`) advances only after a batch is durably processed, so a crash mid-batch safely re-delivers — and re-delivery is harmless by construction.

## Alternatives Considered
- **Check-then-insert in application code**: rejected — classic race: two consumers (or a retry racing the original) both read "not present" and both insert; the window is unavoidable without the constraint anyway.
- **Exactly-once delivery as the guarantee**: rejected — broker exactly-once semantics (idempotent producers/transactional consumers) do not extend to side effects in the database across all failure modes; the portable, broker-agnostic guarantee is an idempotent *consumer*, which also survives replays from the DLQ or a rebuilt topic.
- **Natural-key upsert (`ON CONFLICT … DO UPDATE`)**: considered — rejected for the current model because ingestion should not silently overwrite a stored transaction with a replayed payload; skip-and-count is the conservative financial behaviour. Revisit for legitimate source-side corrections.
- **Client-side idempotency keys passed through the publisher**: redundant — the event already carries `(source_id, source_transaction_id)`; an extra key adds ceremony without adding safety.
- **De-duplication by payload hash**: rejected — unstable under cosmetic source changes (see ADR 005 alternatives in ADR 003 for hashing); conflates identity with content.

## Consequences
**Positive**
- Any delivery attempt — retry, replay, redelivery, re-run of the scheduler — is safe; duplicates are skipped and logged.
- Consumer scaling (more partitions/consumers) cannot create duplicates.
- The DB constraint is testable in isolation (`TransactionRepositoryTest`: same-source duplicate rejected, same ID across sources allowed).

**Negative**
- A source reusing an ID within itself for a *different* transaction would be silently dropped — sources must guarantee intra-source uniqueness (documented assumption).
- Every ingestion write pays for a unique-index maintenance cost (accepted).
- "Skipped duplicate" vs "failed write" must be distinguished by exception type — the consumer must catch integrity violations narrowly and never swallow unrelated constraint failures (e.g., FK violations).
