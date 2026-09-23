# ADR 009: Kafka Messaging Behind an Application-Owned Port

## Status
Accepted

## Context
The assessment's reference stack includes Kafka, and the ingestion pipeline needs to decouple "fetch from source" from "categorize and persist" so a slow consumer or a burst of records does not block the fetch loop, and so failures can be quarantined. Equally important: the *decision to use Kafka* must not bleed into domain and application code. Kafka could later be replaced (RabbitMQ, SQS, Pub/Sub) without a domain rewrite. We must also be honest that we are choosing *more* infrastructure than the minimal alternative — so the choice needs an explicit rationale, including what we are *not* doing yet (the outbox pattern).

## Decision
Kafka **is included**, per the assessment requirements and operational benefits — but only as an implementation detail behind an application-owned messaging port:

1. **Port**: the application defines `TransactionEventPublisher` (naming the event type in the contract: `TransactionIngestedEvent`), with no Kafka types in its signature.
2. **Implementation**: `KafkaTransactionEventPublisher` produces to topic **`transactions.ingested`**; a Spring Kafka listener consumes, categorizes, and persists idempotently (ADR 005). Quarantined/poison events route to **`transactions.dlq`**.
3. **Delivery semantics**: **at-least-once** both producer→broker and broker→consumer; correctness relies on the idempotent consumer, not on broker exactly-once claims.
4. **Partitioning/event identity**: events carry `(source_id, source_transaction_id)` as their natural identity; consumers treat redelivery as normal.
5. **Outbox pattern: deferred.** The current publish path does not use a transactional outbox — the store-then-publish window is acknowledged (see Consequences) and the outbox is recorded as planned evolution, not implemented.

### The alternative that was rejected: no Kafka, pure synchronous pipeline
The straightforward design — scheduler fetches, normalizes, categorizes, and writes to PostgreSQL in one synchronous loop — was seriously considered and **rejected** for this assessment (it remains the natural fallback if Kafka is ever removed through the port).

## Alternatives Considered
- **No Kafka — pure synchronous pipeline** (fetch → persist in one process tick): rejected — a single slow/failed stage stalls the whole cycle; no natural quarantine point for poison messages; consumer-side retry/backoff semantics would be hand-rolled; the assessment specifically targets event-driven integration competence. (Would simplify ops: one less stateful system. If chosen, the `TransactionEventPublisher` port would simply be backed by a direct/in-process implementation.)
- **RabbitMQ / SQS / NATS as the broker**: viable behind the same port — rejected *today* only because Kafka is the reference stack requirement; nothing in the domain prefers Kafka's model, which is exactly the point of the port.
- **Kafka with domain code calling the producer directly** (spring-kafka types throughout application/domain): rejected — broker lock-in, untestable without a broker, contradicts the port architecture.
- **Exactly-once Kafka semantics (transactional producer + read_committed)** as the correctness mechanism: rejected as *the* mechanism — does not by itself make the *database* side effects idempotent across all failure modes; complements but never replaces the DB constraint.
- **Transactional outbox + CDC (Debezium) now**: rejected as *scope* — the correct production-grade answer for zero publish-loss, but it adds a connector runtime and table-contract complexity before it is needed; explicitly deferred rather than silently omitted.

## Consequences
**Positive**
- Broker is swappable: replacing Kafka means writing one new `TransactionEventPublisher` implementation; domain/application untouched.
- Decoupled pacing: fetch loops finish quickly; the consumer absorbs bursts and applies its own retry/backoff.
- Poison isolation is first-class (`transactions.dlq` + `ingestion_error`), matching the failure-classification model.
- Demonstrates port/adapter discipline on the write path, mirroring `TransactionSource` on the read-from-source path.

**Negative / accepted risks**
- **Publish-loss window**: a crash between DB commit and Kafka send can drop an event until the next scheduled cycle re-publishes; the outbox would close this — deferred (see ADR 009 Decision §5).
- Operational cost of a stateful broker (partitions, consumer groups, retention, schema evolution) in an environment that previously had none — mitigated by Compose-managed Kafka for local/demo and idempotent replay from `source_sync_state` cursors if topic data is lost.
- At-least-once means downstream consumers must stay idempotent forever — already enforced by the unique constraint, but any *new* consumer of the same topic inherits the obligation (documented here as the contract).
