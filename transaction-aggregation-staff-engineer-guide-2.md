# Transaction Aggregation API
## Staff Engineer-Level Design, Implementation, Deployment & Operations Guide

**Reference stack**

- Java 21+
- Spring Boot 4
- Spring Kafka
- PostgreSQL
- Flyway
- Docker / Docker Compose
- OpenAPI
- JUnit 5
- Testcontainers
- Micrometer
- OpenTelemetry
- Prometheus-compatible metrics
- Grafana-compatible dashboards

**Messaging principle:** Kafka is the default demonstration broker, but the application should be designed around a **messaging port/interface**, not Kafka-specific domain logic. RabbitMQ, Amazon SQS/SNS, Google Pub/Sub, Azure Service Bus, NATS, or another broker should be substitutable without rewriting the domain and application layers.

## Staff Engineer-Level Design & Implementation Guide

**Project:** Transaction Aggregation API  
**Purpose:** Technical assessment / Staff Engineer design exercise  
**Primary objective:** Demonstrate architectural judgment, domain modelling, data consistency thinking, resilience, API design, and pragmatic engineering rather than merely producing a working CRUD service.

---

# 1. Executive Summary

The project is a system that aggregates customer financial transaction data from multiple mock data sources, normalizes the heterogeneous source data into a canonical transaction model, categorizes transactions, stores the resulting data, and exposes an API for querying transactions and aggregated information.

The important distinction is that this should **not** be approached as simply:

> "Call three APIs and merge their responses."

A Staff Engineer-level solution should frame the problem as:

> **A reliable transaction aggregation platform where heterogeneous financial data sources are normalized into a coherent transaction model, categorized consistently, and exposed through a query-oriented API with explicit consistency, provenance, freshness, resilience, and data-quality semantics.**

The architecture should deliberately answer:

- What does "aggregate" mean?
- What is the system's consistency model?
- What constitutes a transaction identity?
- How is duplicate ingestion prevented?
- Can transactions from different sources be safely deduplicated?
- How is transaction data normalized?
- How are transactions categorized?
- What happens when one source is unavailable?
- What happens when a source returns malformed data?
- How fresh is the aggregated data?
- How do we expose partial or stale results without misleading consumers?
- Which guarantees belong in the database?
- Which guarantees belong in application code?
- Where should asynchronous processing be introduced, and where would it be unnecessary complexity?
- How do we scale the system without prematurely turning it into a distributed system?
- How do we preserve data provenance and explain why a transaction looks the way it does?

The recommended starting architecture is a **modular monolith backed by PostgreSQL**, with source adapters, an ingestion pipeline, normalization, idempotency, categorization, a canonical transaction store, and a query-oriented API.

Kafka or another broker should not be introduced merely because this is a financial system. Add infrastructure when a demonstrated requirement justifies it.

---

# 2. What the Assessment Should Demonstrate

The implementation should demonstrate more than functional correctness.

A strong solution should show:

## 2.1 Architectural judgment

You should be able to explain:

- why you chose the architecture
- why you did not choose alternatives
- what assumptions you made
- what trade-offs you accepted
- where the architecture would change as scale increases

## 2.2 Domain thinking

You should be able to clearly define:

- customer
- account
- transaction
- transaction source
- source transaction identity
- canonical transaction identity
- category
- transaction status
- ingestion status
- provenance

## 2.3 Data correctness

The system should guarantee:

- no accidental duplicate ingestion
- deterministic normalization
- correct monetary arithmetic
- source provenance
- explicit handling of malformed data
- reproducible categorization
- clear treatment of conflicting source information

## 2.4 Resilience

You should demonstrate:

- source timeouts
- retry strategy
- failure isolation
- malformed payload handling
- idempotent retries
- partial aggregation
- stale-data awareness

## 2.5 API design

The API should provide:

- transaction retrieval
- filtering
- pagination
- sorting
- customer scoping
- category filtering
- date filtering
- transaction summaries
- source/freshness information where useful

## 2.6 Operational thinking

Include consideration of:

- structured logging
- metrics
- tracing
- ingestion health
- source freshness
- failed records
- retry counts
- processing latency
- API latency
- database health

---

# 3. Start With the Problem, Not the Technology

Do not begin by deciding:

- Spring Boot
- PostgreSQL
- Kafka
- Redis
- Kubernetes
- microservices

Instead begin by defining the system's behavior.

A good Staff Engineer discussion starts with questions.

---

# 4. Clarify the Meaning of "Aggregate"

There are three broad architectural interpretations.

## 4.1 Runtime aggregation

Every API request calls every source.

```text
Client
  |
  v
Aggregation API
  |
  +----> Source A
  |
  +----> Source B
  |
  +----> Source C
  |
  v
Merge
  |
  v
Response
```

### Advantages

- always queries the source directly
- no ingestion pipeline
- little persistent state

### Disadvantages

- API availability depends on all sources
- slowest source controls latency
- source outages directly impact customers
- repeated work
- difficult pagination
- difficult historical querying
- difficult consistent aggregation
- source rate limits become API rate limits
- difficult auditing
- categorization happens repeatedly unless cached

This approach is generally unattractive once the system has meaningful requirements.

---

# 4.2 Materialized aggregation

Sources are ingested into our system.

```text
Source A ─┐
Source B ─┼──> Ingestion
Source C ─┘       |
                  v
             Normalization
                  |
                  v
             Categorization
                  |
                  v
             PostgreSQL
                  |
                  v
                  API
```

### Advantages

- API is independent of source availability
- efficient querying
- easier pagination
- easier aggregation
- source failures are isolated
- historical data can be retained
- provenance can be stored
- categorization can be performed once
- ingestion can be retried independently

### Disadvantages

- eventual consistency
- more state
- ingestion pipeline required
- freshness must be managed

For this assessment, this is the preferred baseline.

---

# 4.3 Hybrid

A mature production system may combine the approaches.

For example:

- historical transactions come from the materialized store
- very recent transactions may be reconciled against upstream sources
- explicit freshness rules determine whether a source should be queried

Do not implement this unless required. Discuss it as a future evolution.

---

# 5. Define the Consistency Model

This is one of the most important Staff Engineer questions.

If three sources report transactions independently, there is no natural globally consistent snapshot unless the sources support one.

Therefore, the system should generally use:

> **Eventual consistency with explicit freshness metadata.**

Example:

```text
Source A
last successful ingestion: 10:02:01

Source B
last successful ingestion: 10:02:05

Source C
last successful ingestion: 09:55:12
```

The API should not imply that all three sources are equally current.

A response could expose:

```json
{
  "data": [...],
  "meta": {
    "consistency": "EVENTUAL",
    "generatedAt": "2026-09-23T09:10:00Z",
    "sources": [
      {
        "source": "source-a",
        "lastSuccessfulSync": "2026-09-23T09:09:45Z"
      },
      {
        "source": "source-b",
        "lastSuccessfulSync": "2026-09-23T09:09:30Z"
      },
      {
        "source": "source-c",
        "lastSuccessfulSync": "2026-09-23T08:55:12Z"
      }
    ]
  }
}
```

The exact response does not need to expose all operational details, but the semantics must be defined.

---

# 6. Define Freshness

Freshness is a business and operational property.

Define:

```text
freshness = current_time - last_successful_source_sync
```

For example:

```text
< 5 minutes       FRESH
5-30 minutes      STALE
> 30 minutes      VERY_STALE
```

These thresholds are illustrative, not universal.

The system should make the thresholds configurable.

More importantly, the API contract should avoid pretending that an aggregate is complete if a source has been unavailable.

---

# 7. Partial Results

Suppose:

```text
Source A: available
Source B: available
Source C: unavailable
```

There are two obvious choices.

## Option A: Fail everything

```http
503 Service Unavailable
```

## Option B: Return partial data

```json
{
  "transactions": [...],
  "meta": {
    "completeness": "PARTIAL",
    "sources": {
      "source-a": "AVAILABLE",
      "source-b": "AVAILABLE",
      "source-c": "UNAVAILABLE"
    }
  }
}
```

For an aggregation system, partial results can be useful.

However:

> **Partial data must never be presented as complete data.**

This is the important design principle.

Whether a particular endpoint returns partial results or fails should be an explicit API contract decision.

---

# 8. Source Adapter Architecture

Never tightly couple the core domain to a mock source's JSON format.

Use an adapter boundary.

```text
                Source Adapter
                     |
        +------------+------------+
        |            |            |
     Source A     Source B     Source C
        |            |            |
        v            v            v
     Raw DTO       Raw DTO       Raw DTO
        |            |            |
        +------------+------------+
                     |
                     v
              Normalization
```

A conceptual interface:

```java
public interface TransactionSource {

    SourceIdentifier source();

    TransactionPage fetchTransactions(
        FetchRequest request
    );
}
```

The exact interface is implementation-specific.

The important architectural property is:

> The domain should not know how Source A represents an amount, merchant, date, or transaction ID.

---

# 9. Canonical Transaction Model

The source systems may represent the same concepts differently.

Example Source A:

```json
{
  "id": "123",
  "amount": 100,
  "currency": "ZAR"
}
```

Source B:

```json
{
  "transactionReference": "ABC-123",
  "value": 100.00,
  "currencyCode": "ZAR"
}
```

The canonical model should represent the domain rather than the source.

Example:

```json
{
  "id": "txn_01J...",
  "customerId": "cust_123",
  "accountId": "acc_456",
  "amount": {
    "value": 100.00,
    "currency": "ZAR"
  },
  "direction": "DEBIT",
  "transactionDate": "2026-09-22T10:31:00Z",
  "description": "MCDONALDS SANDTON",
  "merchant": {
    "name": "McDonald's"
  },
  "category": {
    "code": "FOOD_AND_DINING"
  },
  "source": {
    "provider": "SOURCE_A",
    "transactionId": "123"
  }
}
```

---

# 10. Transaction Identity

This is one of the most important data modelling decisions.

Do not assume a source transaction ID is globally unique.

A safe source-level identity is:

```text
(source_id, source_transaction_id)
```

For example:

```text
SOURCE_A / 12345
SOURCE_B / 12345
```

are potentially two different transactions.

Therefore the database should enforce:

```text
UNIQUE(source_id, source_transaction_id)
```

The system can additionally generate an internal canonical ID:

```text
transaction_id = UUID
```

Conceptually:

```text
Canonical Transaction
        |
        +--- SOURCE_A / 12345
```

---

# 11. Cross-Source Deduplication

This is where requirements need discipline.

If:

```text
Source A:
12345 / R500 / 10:00 / Woolworths

Source B:
ABC99 / R500 / 10:00 / Woolworths
```

are these the same transaction?

Not necessarily.

Unless there is a reliable correlation key, the system should **not invent certainty**.

Recommended principle:

> Guarantee idempotency within each source. Perform cross-source deduplication only when a documented correlation strategy exists.

This is much safer than fuzzy matching financial records.

---

# 12. Provenance

Every normalized transaction should retain enough information to answer:

> Where did this value come from?

At minimum:

```text
source
sourceTransactionId
ingestedAt
```

Potentially:

```text
sourcePayloadVersion
sourceUpdatedAt
normalizationVersion
categorizationVersion
```

A useful conceptual model:

```text
Transaction
  |
  +-- Source
  +-- Source Transaction ID
  +-- Ingested At
  +-- Normalization Version
  +-- Categorization Version
```

This supports debugging, auditing, reconciliation, and future reprocessing.

---

# 13. Raw vs Canonical Data

A strong design should consider whether to retain the original source representation.

There are two approaches.

## Canonical only

Store only the normalized representation.

### Pros

- simpler
- lower storage requirements
- less sensitive data duplication

### Cons

- harder to debug normalization
- difficult to reproduce old processing behavior
- less provenance

## Raw + canonical

Store:

```text
Raw source record
       |
       v
Normalization
       |
       v
Canonical record
```

### Pros

- reproducibility
- easier debugging
- replay
- auditing

### Cons

- more storage
- potentially more sensitive data
- retention and privacy requirements become important

For a take-home assessment, you can document the decision even if you do not implement a raw payload store.

---

# 14. Categorization

Categorization should be isolated from ingestion.

Recommended conceptual pipeline:

```text
Raw Transaction
      |
      v
Normalization
      |
      v
Canonical Transaction
      |
      v
Categorization
      |
      v
Categorized Transaction
```

Do not embed all categorization rules directly inside the source adapters.

---

# 15. Categorization Strategy

A simple first version can use deterministic rules.

Example:

```text
IF merchant contains "WOOLWORTHS"
    -> GROCERIES

IF merchant contains "UBER"
    -> TRANSPORT

IF merchant contains "NETFLIX"
    -> ENTERTAINMENT
```

Use an abstraction:

```java
public interface TransactionCategorizer {

    CategoryResult categorize(Transaction transaction);
}
```

This creates an extension point.

---

# 16. Category Explainability

A useful extension is to retain:

```json
{
  "category": {
    "code": "FOOD_AND_DINING",
    "rule": "MERCHANT_MATCH_MCDONALDS",
    "version": "3"
  }
}
```

You do not necessarily need to expose the internal rule through the public API.

But the architecture should allow the system to answer:

> Why was this transaction categorized this way?

This becomes increasingly important as categorization becomes more sophisticated.

---

# 17. Category Versioning

If categorization logic changes:

```text
Version 1:
UBER -> TRANSPORT

Version 2:
UBER EATS -> FOOD_DELIVERY
UBER -> TRANSPORT
```

Historical results can change depending on when they were categorized.

Decide whether:

### Historical categorization is immutable

Transactions retain the category assigned at processing time.

### Categorization is recalculated

All transactions can be reprocessed using a newer version.

For a financial system, this decision should be explicit.

A strong baseline:

> Persist the category and categorization version assigned to each transaction. Support explicit reprocessing rather than silently changing historical classifications.

---

# 18. Monetary Values

Never represent money as floating point.

Avoid:

```java
double amount;
```

Use:

```java
BigDecimal
```

or an integer minor-unit representation where appropriate.

For currencies with two decimal places:

```text
R100.50 -> 10050 cents
```

However, minor-unit storage should not be assumed universally because currencies have different decimal rules.

A robust model is:

```text
Money
  value
  currency
```

with currency-specific validation.

---

# 19. Currency

If multiple currencies are supported, do not silently convert them.

Example:

```text
USD 100
ZAR 1800
EUR 90
```

An aggregate:

```text
total = 3790
```

is meaningless.

Therefore:

```json
{
  "currency": "ZAR",
  "totalDebits": 10000.00
}
```

is safer than aggregating different currencies into one number.

If conversion is required, introduce:

```text
Exchange Rate
Conversion Date
Rate Provider
Rate Version
```

and make the conversion semantics explicit.

Do not add currency conversion if the brief does not require it.

---

# 20. Transaction Direction

A transaction should distinguish:

```text
DEBIT
CREDIT
```

rather than relying solely on positive/negative amounts.

For example:

```json
{
  "amount": 500.00,
  "direction": "DEBIT"
}
```

or:

```json
{
  "amount": 500.00,
  "direction": "CREDIT"
}
```

This makes domain semantics clearer.

---

# 21. Suggested Domain Model

A reasonable starting model:

```text
Customer
  |
  +--- Account
         |
         +--- Transaction
                  |
                  +--- Category
                  |
                  +--- TransactionSource
```

Depending on the mock source contract, customer and account may be references rather than fully owned aggregates.

The key domain object is the transaction.

---

# 22. Suggested Transaction Fields

Example:

```text
Transaction
------------
id
customerId
accountId
sourceId
sourceTransactionId
transactionDate
postedAt
amount
currency
direction
description
merchantName
categoryCode
categoryVersion
status
createdAt
updatedAt
```

Not all fields need to be implemented if the source data does not provide them.

Do not invent business semantics simply to make the model look sophisticated.

---

# 23. Database Constraints

Use the database to enforce invariants.

Examples:

```sql
UNIQUE(source_id, source_transaction_id)
```

Potential constraints:

```text
amount > 0
currency NOT NULL
source_id NOT NULL
source_transaction_id NOT NULL
transaction_date NOT NULL
```

Do not rely exclusively on application-level checks.

If uniqueness matters, enforce uniqueness at the database layer.

---

# 24. Idempotency

Suppose the ingestion process receives:

```text
Transaction 123
```

Then retries and receives:

```text
Transaction 123
```

The result should remain:

```text
One canonical transaction
```

not:

```text
Two canonical transactions
```

The database uniqueness constraint is the final safety net.

The ingestion process should be designed so that retries are safe.

---

# 25. Avoid Check-Then-Insert Race Conditions

Do not rely solely on:

```java
if (!repository.existsBySourceAndSourceId(...)) {
    repository.save(...);
}
```

Two workers can execute the check simultaneously.

Instead:

```text
Worker A ----\
              >--- INSERT --- database uniqueness constraint
Worker B ----/
```

The database enforces the invariant.

Application code should handle the duplicate outcome appropriately.

---

# 26. Ingestion Model

A simple initial ingestion model:

```text
Scheduler
    |
    +---- Source A
    |
    +---- Source B
    |
    +---- Source C
    |
    v
Normalize
    |
    v
Categorize
    |
    v
Persist
```

The scheduler could run every N minutes.

The exact frequency should be configurable.

---

# 27. Incremental Ingestion

Do not repeatedly ingest the entire source history if the source supports incremental retrieval.

Prefer:

```text
GET /transactions?from=...&to=...
```

or:

```text
GET /transactions?cursor=...
```

Store ingestion state:

```text
SourceSyncState
----------------
sourceId
lastSuccessfulSync
cursor
status
failureCount
lastError
```

This allows incremental ingestion.

---

# 28. Source Synchronization State

A useful conceptual model:

```text
Source A
  |
  +-- lastSuccessfulSync
  +-- lastAttempt
  +-- currentCursor
  +-- failureCount
  +-- status
```

Example:

```json
{
  "source": "SOURCE_A",
  "status": "HEALTHY",
  "lastSuccessfulSync": "2026-09-23T09:15:00Z",
  "lastAttempt": "2026-09-23T09:15:05Z",
  "failureCount": 0
}
```

This information becomes valuable operationally and for API freshness semantics.

---

# 29. Failure Classification

Not all failures should be retried.

## Retryable

- connection timeout
- connection reset
- HTTP 502
- HTTP 503
- temporary network failure
- rate limiting, with appropriate backoff

## Non-retryable

- malformed payload
- invalid transaction
- authentication failure requiring configuration
- unsupported schema
- invalid customer identifier

A useful rule:

> Retry infrastructure failures. Quarantine data failures.

---

# 30. Retry Strategy

Use bounded retries.

Conceptually:

```text
attempt 1
   |
   v
wait
   |
attempt 2
   |
   v
wait
   |
attempt 3
   |
   v
failure
```

Use exponential backoff with jitter where appropriate.

Do not create infinite retry loops.

---

# 31. Circuit Breaking

If a source remains unavailable, repeated retries can make the situation worse.

A circuit breaker can transition:

```text
CLOSED
   |
   | failures
   v
OPEN
   |
   | cooldown
   v
HALF_OPEN
   |
   +---- success ---> CLOSED
   |
   +---- failure ---> OPEN
```

Whether to implement a circuit breaker in the assessment depends on scope.

It is worth discussing even if the implementation remains simple.

---

# 32. Malformed Source Data

Example:

```json
{
  "id": "123",
  "amount": "banana",
  "currency": null
}
```

Do not allow malformed records to crash the entire ingestion batch.

Instead:

```text
Source batch
   |
   +-- Valid record -> process
   |
   +-- Invalid record -> quarantine
```

Record:

```text
source
sourceTransactionId
failureReason
raw/error context
receivedAt
```

Do not necessarily expose raw financial data through operational APIs.

---

# 33. Batch Failure Semantics

Suppose a source returns 10,000 transactions and transaction 7,201 is invalid.

You should not necessarily reject the entire batch.

Prefer:

```text
9,999 processed
1 quarantined
```

assuming the source contract allows independent record processing.

This makes the system resilient to bad individual records.

---

# 34. API Design Principles

The API should be consumer-oriented.

Avoid designing endpoints purely around database tables.

Potential endpoints:

```http
GET /v1/customers/{customerId}/transactions
GET /v1/customers/{customerId}/transactions/{transactionId}
GET /v1/customers/{customerId}/transactions/summary
GET /v1/customers/{customerId}/categories
```

Potential administrative/operational endpoints:

```http
GET /v1/admin/sources
GET /v1/admin/sources/{sourceId}/status
```

Keep administrative endpoints separate from customer-facing semantics.

---

# 35. Transaction Query API

Example:

```http
GET /v1/customers/cust-123/transactions
    ?from=2026-09-01
    &to=2026-09-23
    &category=GROCERIES
    &direction=DEBIT
    &minAmount=100
    &maxAmount=5000
    &sort=transactionDate,desc
    &page=0
    &size=50
```

Avoid unbounded result sets.

Always establish pagination semantics.

---

# 36. Pagination

Offset pagination:

```text
?page=0&size=50
```

is simple.

For large datasets, cursor pagination can be more robust:

```text
?cursor=eyJ0aW1lc3RhbXAiOi...
```

A reasonable assessment implementation can start with offset pagination and document cursor pagination as a future evolution.

If the dataset is expected to become very large, discuss the trade-off.

---

# 37. Stable Sorting

Pagination requires deterministic ordering.

Do not sort only by:

```text
transactionDate
```

because multiple transactions can share the same timestamp.

Prefer:

```text
transactionDate DESC, id DESC
```

This creates a stable ordering.

---

# 38. Filtering

Useful filters include:

```text
from
to
category
direction
merchant
source
currency
minAmount
maxAmount
status
```

Do not implement every conceivable filter.

Implement the filters that correspond to real consumer use cases.

---

# 39. Summary API

Example:

```http
GET /v1/customers/{customerId}/transactions/summary
    ?from=2026-09-01
    &to=2026-09-23
```

Response:

```json
{
  "period": {
    "from": "2026-09-01",
    "to": "2026-09-23"
  },
  "transactionCount": 147,
  "totalDebits": 84250.50,
  "totalCredits": 125000.00,
  "byCategory": [
    {
      "category": "GROCERIES",
      "count": 31,
      "amount": 12450.00
    }
  ]
}
```

If multiple currencies exist, aggregate separately by currency.

---

# 40. Query-Time vs Materialized Aggregates

For the first version:

```text
PostgreSQL
    |
    +--- transaction queries
    |
    +--- SQL aggregation
```

This is usually sufficient.

Do not immediately create:

```text
Kafka
  |
  v
Stream processor
  |
  v
Redis
  |
  v
ClickHouse
```

unless requirements justify it.

A good architectural principle:

> Start with the simplest architecture that satisfies the required performance and correctness characteristics.

---

# 41. When Materialized Aggregates Become Justified

Consider them when:

- summary queries become expensive
- transaction volume becomes very large
- aggregation queries dominate workload
- near-real-time dashboards are required
- read/write patterns diverge substantially
- query latency requirements become strict

Then you might introduce:

```text
Transaction Store
      |
      v
Aggregation Processor
      |
      v
Read Model
```

But that should be an evolution, not the starting point.

---

# 42. Recommended Architecture

Baseline:

```text
                         ┌──────────────────┐
                         │    REST Client   │
                         └────────┬─────────┘
                                  |
                                  v
                         ┌──────────────────┐
                         │   Transaction    │
                         │      API         │
                         └────────┬─────────┘
                                  |
                    ┌─────────────┴─────────────┐
                    |                           |
                    v                           v
             Transaction Query             Summary Query
                    |                           |
                    +-------------+-------------+
                                  |
                                  v
                         ┌──────────────────┐
                         │   Transaction    │
                         │    Repository    │
                         └────────┬─────────┘
                                  |
                                  v
                         ┌──────────────────┐
                         │    PostgreSQL    │
                         └──────────────────┘


      ┌─────────────────────────────────────────────┐
      │              INGESTION SIDE                 │
      └─────────────────────────────────────────────┘

     ┌───────────┐    ┌───────────┐    ┌───────────┐
     │ Source A  │    │ Source B  │    │ Source C  │
     └─────┬─────┘    └─────┬─────┘    └─────┬─────┘
           |                |                |
           v                v                v
     ┌─────────────────────────────────────────────┐
     │              Source Adapters                │
     └──────────────────────┬──────────────────────┘
                            |
                            v
                    ┌───────────────┐
                    │ Normalization │
                    └───────┬───────┘
                            |
                            v
                    ┌───────────────┐
                    │ Identity /    │
                    │ Idempotency   │
                    └───────┬───────┘
                            |
                            v
                    ┌───────────────┐
                    │ Categorization│
                    └───────┬───────┘
                            |
                            v
                    ┌───────────────┐
                    │ PostgreSQL    │
                    └───────────────┘
```

---

# 43. Modular Monolith

For this project, a modular monolith is an excellent starting point.

Potential structure:

```text
transaction-aggregation/
├── src/main/java/...
│
├── ingestion/
│   ├── source/
│   ├── normalization/
│   ├── synchronization/
│   └── scheduling/
│
├── transaction/
│   ├── domain/
│   ├── application/
│   ├── persistence/
│   └── query/
│
├── categorization/
│   ├── domain/
│   ├── application/
│   └── rules/
│
├── aggregation/
│   ├── application/
│   └── query/
│
├── api/
│   ├── transaction/
│   └── administration/
│
└── infrastructure/
    ├── database/
    ├── http/
    └── configuration/
```

The exact package layout is less important than clear boundaries.

---

# 44. Why Not Microservices?

A Staff Engineer should be able to explain this.

Splitting into:

```text
transaction-service
source-service
categorization-service
aggregation-service
ingestion-service
```

introduces:

- network boundaries
- distributed transactions
- service discovery
- deployment complexity
- observability requirements
- failure modes
- versioning
- additional operational cost

For the assessment, these costs are probably not justified.

A modular monolith gives:

- clear domain boundaries
- simple deployment
- simple transactions
- easy local development
- easy testing
- future extraction points

---

# 45. Where a Service Boundary Could Emerge Later

If categorization becomes computationally expensive:

```text
Transaction Service
        |
        v
Categorization Service
```

If ingestion needs independent scaling:

```text
Ingestion Workers
        |
        v
Transaction Store
```

If summary workloads become massive:

```text
Transaction Store
        |
        v
Analytics Pipeline
```

The modular design should make those extractions possible without requiring them today.

---

# 46. Messaging and Kafka

Do not use Kafka simply because:

> "Financial systems use Kafka."

Introduce messaging when there is a real requirement for:

- asynchronous processing
- workload buffering
- replay
- multiple consumers
- independent scaling
- event-driven integration
- decoupling ingestion from processing

A reference event-driven architecture can be:

```text
Source
  |
  v
Ingestion
  |
  v
Kafka
  |
  +----> Normalization
  |
  +----> Categorization
  |
  +----> Audit
  |
  +----> Analytics
```

For this assessment, Kafka is intentionally included so the solution demonstrates asynchronous processing, replayability, consumer isolation, and event-driven design.

The important constraint is that Kafka remains replaceable through a messaging port.



---

# 47. Outbox Pattern

If the system eventually needs to publish transaction events after database persistence:

```text
Transaction
     |
     +---- DB transaction ----+
     |                        |
     v                        v
Transactions             Outbox
table                    table
                              |
                              v
                         Publisher
                              |
                              v
                            Kafka
```

The outbox pattern avoids:

```text
DB commit succeeds
Kafka publish fails
```

leaving the system in an inconsistent state.

Again, this is an evolution path, not necessarily required for the first implementation.

---

# 48. Database Schema

A starting schema might look like:

```text
transaction
-----------
id
customer_id
account_id
source_id
source_transaction_id
transaction_date
posted_at
amount
currency
direction
description
merchant_name
category_code
category_version
status
created_at
updated_at
```

Source:

```text
transaction_source
-------------------
id
name
status
last_successful_sync
last_attempted_sync
failure_count
cursor
created_at
updated_at
```

Optional quarantine:

```text
ingestion_error
---------------
id
source_id
source_transaction_id
error_type
error_message
occurred_at
retryable
```

---

# 49. Database Indexing

Indexes should reflect actual access patterns.

Likely indexes:

```text
(customer_id, transaction_date DESC)
```

Potentially:

```text
(customer_id, category_code, transaction_date DESC)
(customer_id, direction, transaction_date DESC)
(source_id, source_transaction_id)
```

Do not create dozens of indexes without measuring query patterns.

Indexes have write and storage costs.

---

# 50. Database Partitioning

Partitioning may eventually become useful if transaction volume becomes very large.

Potential partition key:

```text
transaction_date
```

For example:

```text
2026-01
2026-02
2026-03
...
```

Do not introduce partitioning in the assessment unless there is a demonstrated need.

Discuss it as a scale path.

---

# 51. API Error Model

Use a consistent error format.

Example:

```json
{
  "type": "https://example.com/problems/invalid-request",
  "title": "Invalid request",
  "status": 400,
  "detail": "The 'from' date must not be after the 'to' date.",
  "instance": "/v1/customers/cust-123/transactions"
}
```

RFC 9457 / Problem Details style is a good model.

---

# 52. Authentication and Authorization

The assessment may not require a full identity platform.

However, the design should establish:

- customer isolation
- authorization boundaries
- administrative access
- source operational access

A crucial invariant:

> A customer must never be able to retrieve another customer's transactions by manipulating an ID or filter.

Authorization should not depend solely on:

```text
GET /customers/{customerId}
```

The service must verify that the authenticated principal has access to that customer.

---

# 53. Tenant / Customer Isolation

Even if multi-tenancy is not explicitly required, model the boundary clearly.

For example:

```text
Authenticated Principal
       |
       v
Customer Access Check
       |
       v
Transaction Query
       |
       v
customer_id = authorized_customer
```

Avoid trusting a client-provided customer ID without authorization.

---

# 54. Security Considerations

Financial transaction data is sensitive.

Consider:

- authentication
- authorization
- TLS
- secrets management
- database access control
- least privilege
- audit logging
- sensitive data minimization
- log redaction
- rate limiting
- input validation

Never log complete transaction payloads casually.

Avoid:

```text
INFO Transaction received: {full customer transaction}
```

Prefer:

```text
INFO transaction_ingested transaction_id=... source=SOURCE_A
```

---

# 55. Observability

At minimum expose metrics around:

## API

```text
request_count
request_latency
error_rate
```

## Ingestion

```text
source_sync_success
source_sync_failure
transactions_received
transactions_processed
transactions_quarantined
duplicate_transactions
```

## Freshness

```text
source_last_successful_sync
source_freshness_seconds
```

## Database

```text
connection_pool_usage
query_latency
```

---

# 56. Structured Logging

Use structured logs.

Example:

```json
{
  "timestamp": "2026-09-23T09:30:00Z",
  "level": "INFO",
  "event": "transaction_ingested",
  "source": "SOURCE_A",
  "sourceTransactionId": "12345",
  "transactionId": "txn_123"
}
```

Avoid logging:

- authentication tokens
- passwords
- full account numbers
- unnecessary personal information
- full raw financial payloads

---

# 57. Distributed Tracing

If the system remains a modular monolith, tracing can still be useful.

Example:

```text
HTTP Request
   |
   +-- DB query
   |
   +-- source ingestion
```

If later decomposed into services, tracing becomes significantly more important.

OpenTelemetry is a good future-proof choice.

---

# 58. Testing Strategy

Testing should reflect the architecture.

## Unit tests

Test:

- normalization
- categorization
- validation
- mapping
- aggregation calculations

## Integration tests

Test:

- PostgreSQL
- database constraints
- repository behavior
- transaction boundaries

## Contract tests

Test:

- source adapter assumptions
- source response formats

## API tests

Test:

- filtering
- pagination
- sorting
- authorization
- error responses

## End-to-end tests

Test:

```text
Source
  |
  v
Ingestion
  |
  v
Normalization
  |
  v
Categorization
  |
  v
PostgreSQL
  |
  v
API
```

---

# 59. Testcontainers

For a realistic backend assessment, use Testcontainers for PostgreSQL rather than relying solely on an in-memory database.

This verifies:

- actual SQL
- actual indexes
- actual constraints
- actual transaction behavior
- migration compatibility

---

# 60. Important Test Cases

At minimum:

### Happy path

- transaction retrieved from Source A
- transaction normalized
- transaction categorized
- transaction persisted
- transaction returned via API

### Duplicate

- same source transaction arrives twice
- only one canonical transaction exists

### Cross-source IDs

- Source A and Source B both use transaction ID `123`
- records remain distinct

### Malformed record

- one invalid record does not invalidate the entire batch

### Source outage

- Source B unavailable
- Source A data still processes

### Retry

- failed source request retries
- successful retry does not create duplicates

### Partial result

- one source is stale/unavailable
- API exposes correct completeness semantics

### Currency

- ZAR transactions aggregate correctly
- different currencies are not silently combined

### Pagination

- deterministic ordering
- no duplicate records across pages

### Authorization

- customer A cannot retrieve customer B's transactions

### Filtering

- category
- date range
- amount
- direction

### Aggregation

- category totals
- debit totals
- credit totals

---

# 61. Property-Based Thinking

You do not necessarily need a property-based testing framework.

But think in invariants.

Examples:

> Processing the same source transaction twice must produce the same final state as processing it once.

> Aggregating transactions must never combine different currencies without explicit conversion.

> A transaction belonging to customer A must never be returned in customer B's authorized result set.

> Replaying an ingestion batch must not increase the number of canonical transactions.

These invariants are more valuable than simply increasing test count.

---

# 62. API Contract Examples

## List transactions

```http
GET /v1/customers/cust-123/transactions?from=2026-09-01&to=2026-09-23
```

Response:

```json
{
  "data": [
    {
      "id": "txn_123",
      "transactionDate": "2026-09-22T10:31:00Z",
      "amount": {
        "value": 500.00,
        "currency": "ZAR"
      },
      "direction": "DEBIT",
      "description": "MCDONALDS SANDTON",
      "category": {
        "code": "FOOD_AND_DINING"
      }
    }
  ],
  "page": {
    "number": 0,
    "size": 50,
    "totalElements": 1,
    "totalPages": 1
  },
  "meta": {
    "consistency": "EVENTUAL"
  }
}
```

---

# 63. Summary Endpoint

```http
GET /v1/customers/cust-123/transactions/summary?from=2026-09-01&to=2026-09-23
```

Response:

```json
{
  "period": {
    "from": "2026-09-01",
    "to": "2026-09-23"
  },
  "currencies": [
    {
      "currency": "ZAR",
      "transactionCount": 147,
      "totalDebits": 84250.50,
      "totalCredits": 125000.00,
      "byCategory": [
        {
          "category": "GROCERIES",
          "transactionCount": 31,
          "amount": 12450.00
        },
        {
          "category": "TRANSPORT",
          "transactionCount": 18,
          "amount": 5200.00
        }
      ]
    }
  ]
}
```

---

# 64. Source Health Endpoint

Potential internal endpoint:

```http
GET /v1/admin/sources
```

Response:

```json
{
  "sources": [
    {
      "id": "SOURCE_A",
      "status": "HEALTHY",
      "lastSuccessfulSync": "2026-09-23T09:15:00Z",
      "freshnessSeconds": 30
    },
    {
      "id": "SOURCE_B",
      "status": "DEGRADED",
      "lastSuccessfulSync": "2026-09-23T08:55:00Z",
      "freshnessSeconds": 1230
    }
  ]
}
```

This can be kept administrative and not exposed to customers.

---

# 65. Technology Recommendation

The reference implementation should deliberately use a realistic but locally runnable stack.

```text
Java 21+
Spring Boot 4
Spring Web
Spring Validation
Spring Data JDBC or Spring Data JPA
PostgreSQL
Flyway
Spring Kafka
Docker Compose
OpenAPI
JUnit 5
Testcontainers
Micrometer
OpenTelemetry
Prometheus
Grafana
```

Optional:

```text
Kafka UI / Redpanda Console
Redis
Traefik / Nginx
```

The optional components should only be introduced when they demonstrate a meaningful engineering concern.

---

# 65.1 Why Spring Boot 4

Spring Boot 4 is the reference application framework.

Use it for:

- REST APIs
- dependency injection
- configuration
- validation
- transaction management
- database access
- Kafka integration
- observability
- health checks
- testing

The application should remain structured so that the domain does not become coupled to Spring.

A useful layering is:

```text
API / Web
    |
    v
Application
    |
    v
Domain
    ^
    |
Infrastructure
```

The dependency direction should generally point toward the domain rather than making the domain depend on infrastructure.

---

# 65.2 PostgreSQL

PostgreSQL is the system of record for the normalized transaction model.

Use it for:

- canonical transactions
- transaction source metadata
- ingestion state
- categorization metadata
- quarantine/error records
- audit metadata
- optional outbox records
- query-time aggregation

Use Flyway for schema migrations.

Do not rely on `ddl-auto=create` or similar approaches in production-oriented environments.

---

# 65.3 Kafka

Kafka is the default messaging implementation for the demonstration.

Kafka can decouple:

```text
Source ingestion
       |
       v
Transaction received
       |
       v
Kafka
       |
       +----> Normalization
       |
       +----> Categorization
       |
       +----> Audit
       |
       +----> Analytics
```

However:

> Kafka must be an infrastructure detail, not a domain abstraction.

The application should define an internal messaging port such as:

```java
public interface TransactionEventPublisher {

    void publish(TransactionEvent event);
}
```

The infrastructure layer can implement it:

```text
TransactionEventPublisher
          |
          +---- KafkaTransactionEventPublisher
          |
          +---- RabbitTransactionEventPublisher
          |
          +---- SqsTransactionEventPublisher
```

This makes the messaging technology replaceable.

---

# 65.4 Kafka Topics

A reasonable demonstration topology could be:

```text
transactions.raw
transactions.normalized
transactions.categorized
transactions.failed
```

However, do not create topics for every domain object merely because Kafka is available.

A simpler initial flow can be:

```text
Source Adapter
     |
     v
transactions.ingested
     |
     v
Normalization
     |
     v
transactions.normalized
     |
     v
Categorization
     |
     v
transactions.categorized
```

Failed processing can use:

```text
transactions.dlq
```

or a retry topic strategy.

---

# 65.5 Kafka Consumer Groups

Consumers should use independent consumer groups when they have independent responsibilities.

Example:

```text
transactions.normalized
        |
        +---- transaction-store-consumer
        |
        +---- audit-consumer
        |
        +---- analytics-consumer
```

This allows consumers to scale independently.

Do not create consumer groups unnecessarily.

---

# 65.6 RabbitMQ Substitution

The application should make it possible to replace Kafka without changing:

- transaction domain objects
- application services
- REST controllers
- business rules
- persistence model

The intended architecture is:

```text
                Application
                    |
                    v
           Messaging Port
                    |
          +---------+---------+
          |                   |
          v                   v
     Kafka Adapter      RabbitMQ Adapter
```

This does not mean Kafka and RabbitMQ are semantically identical.

Their delivery, ordering, partitioning, routing, retention, replay, and acknowledgement models differ.

The abstraction should therefore expose the **business-level messaging contract**, not a fake generic Kafka/Rabbit API.

For example, avoid:

```java
publish(String topic, int partition, byte[] payload)
```

in the domain/application layer.

Prefer:

```java
publish(TransactionIngested event)
```

and let infrastructure translate that into the chosen broker's semantics.

---

# 65.7 Messaging Delivery Semantics

The system should explicitly document its delivery guarantee.

A sensible baseline:

> At-least-once delivery with idempotent consumers.

This means a consumer may receive an event more than once.

Therefore:

```text
Kafka
  |
  +---- event 123
  +---- event 123
          |
          v
     Idempotent Consumer
          |
          v
       Database
```

The database remains the final correctness boundary.

Do not assume "exactly once" simply because Kafka offers exactly-once features.

Exactly-once semantics are highly contextual and do not automatically mean exactly-once business effects across arbitrary external systems.

---

# 65.8 Event Identity

Every event should have an explicit identity.

Example:

```json
{
  "eventId": "01J...",
  "eventType": "TransactionIngested",
  "occurredAt": "2026-09-23T09:30:00Z",
  "source": "SOURCE_A",
  "payload": {}
}
```

Consumers can use:

```text
consumer
  +
eventId
```

to enforce idempotency.

A database table can record processed events if necessary:

```text
processed_event
---------------
consumer_name
event_id
processed_at

UNIQUE(consumer_name, event_id)
```

Whether a dedicated table is required depends on the processing model.

---

# 65.9 Kafka Partitioning

Partitioning should follow the ordering requirement.

Potential partition key:

```text
customerId
```

or:

```text
accountId
```

This can preserve ordering for transactions belonging to the same customer/account.

But partitioning by customer can create hot partitions if a small number of customers have disproportionately high traffic.

Document the choice.

Do not claim global ordering.

Kafka provides ordering within a partition, not across the topic.

---

# 65.10 Kafka Retention and Replay

One of Kafka's useful properties is replayability.

This makes it possible to rebuild downstream state:

```text
Kafka
  |
  v
Reprocess
  |
  v
Transaction Store
```

This can be valuable if:

- categorization rules change
- a downstream consumer loses data
- a new read model is introduced
- analytics requirements change

However, replay should be designed carefully because replaying events can have side effects.

Consumers must remain idempotent.

# 66. Why PostgreSQL?

PostgreSQL is a good fit because the workload is initially:

- transactional
- relational
- query-oriented
- aggregation-heavy
- consistency-sensitive
- moderate complexity

It provides:

- strong constraints
- transactions
- indexes
- SQL aggregation
- JSON support if needed
- excellent tooling

A document database is not automatically better simply because source data is heterogeneous.

Normalize the data into a canonical model and use PostgreSQL for the query model.

---

# 67. Why Not Store Everything as JSON?

A tempting shortcut is:

```text
transaction
-----------
id
source
raw_json
```

This makes ingestion easy but pushes complexity into every query.

You lose:

- strong typing
- database constraints
- efficient indexing
- clear domain semantics
- straightforward aggregation

A better approach is:

```text
Canonical fields
+
optional raw source metadata
```

---

# 68. Caching

Do not add Redis by default.

Caching can introduce:

- invalidation
- stale data
- consistency questions
- additional infrastructure

Start without it.

Add caching if:

- summary queries become expensive
- repeated identical queries dominate traffic
- freshness requirements permit caching

If cached, define the maximum acceptable staleness.

---

# 69. Security and Privacy

Treat financial transaction data as sensitive.

Consider data retention.

For example:

```text
Raw source data retention: 30 days
Canonical transaction retention: 7 years
```

These values are examples only and should come from actual business/regulatory requirements.

Do not invent regulatory compliance claims.

Instead state:

> Retention, deletion, encryption, and audit requirements must be determined from the jurisdiction and business context.

---

# 70. Data Retention

Ask:

- How long should transactions remain available?
- Can transactions be deleted?
- Are they immutable?
- Is correction allowed?
- Must historical categorization remain reproducible?
- Should raw source payloads have a shorter retention period?

This can become an important domain discussion.

---

# 71. Immutable vs Mutable Transactions

Financial transaction data often benefits from treating core facts as immutable.

For example:

```text
amount
currency
source
sourceTransactionId
transactionDate
```

should generally not be casually overwritten.

If a source corrects a transaction, consider:

```text
version
sourceUpdatedAt
```

or a correction/event model.

Do not implement a complex event-sourced ledger unless the requirements demand it.

---

# 72. Versioning

Potentially version:

```text
source schema
normalization logic
categorization logic
API
```

API:

```text
/v1/...
```

Categorization:

```text
categoryVersion = 3
```

Normalization:

```text
normalizationVersion = 2
```

This becomes valuable when historical results need to be explained or reproduced.

---

# 73. Source Schema Evolution

Suppose Source A changes:

```json
"amount": 100
```

to:

```json
"value": {
  "amount": 100,
  "currency": "ZAR"
}
```

The adapter should absorb that change.

The domain model should not need to know.

This is one of the main reasons to isolate source adapters.

---

# 74. Contract Validation

At the source boundary:

```text
External DTO
     |
     v
Schema validation
     |
     v
Mapping
     |
     v
Canonical domain
```

Never allow an external source representation to leak deep into the domain.

---

# 75. Ingestion Observability

For every source sync, capture:

```text
source
startedAt
completedAt
duration
recordsReceived
recordsProcessed
recordsDuplicated
recordsRejected
recordsCategorized
status
error
```

Example:

```text
SOURCE_A
------------------------------
Duration: 1.8s
Received: 10,000
Processed: 9,998
Duplicates: 1
Rejected: 1
Status: SUCCESS_WITH_WARNINGS
```

This is extremely useful during an assessment because it demonstrates operational thinking.

---

# 76. Backpressure

If source APIs can return very large datasets, avoid loading everything into memory.

Prefer:

```text
source page
    |
    v
process page
    |
    v
persist page
    |
    v
next page
```

rather than:

```text
fetch 10 million records
       |
       v
load everything into RAM
```

If concurrency is introduced, bound it.

---

# 77. Concurrency

If multiple ingestion workers run simultaneously, protect against:

- duplicate processing
- database contention
- source rate limits
- excessive memory usage

Options include:

- database locks
- scheduler-level single execution
- distributed locks if multiple application instances exist
- idempotent writes

Do not add distributed locking unless multiple instances actually require it.

---

# 78. Scaling Model

The initial system:

```text
             Load Balancer
                   |
            +------+------+
            |             |
        App Instance  App Instance
            |             |
            +------+------+
                   |
               PostgreSQL
```

The API is stateless.

Ingestion coordination requires additional thought if multiple application instances run scheduled jobs.

Options:

1. single dedicated ingestion worker
2. distributed scheduler
3. database-based locking
4. queue-based ingestion

Choose the simplest approach that satisfies the deployment model.

---

# 79. Horizontal Scaling

The API layer should ideally be stateless.

That means:

```text
Client
  |
  +----> Instance A
  |
  +----> Instance B
  |
  +----> Instance C
```

All instances access the same durable data store.

Avoid local state that is required for correctness.

---

# 80. Availability vs Correctness

Do not optimize blindly for availability.

A transaction aggregation API that returns incorrect financial data is potentially worse than one that temporarily reports:

```text
PARTIAL
```

or:

```text
503
```

The system should make correctness guarantees explicit.

---

# 81. Failure Scenario Walkthroughs

Use these to challenge your design.

## Scenario 1: Source A is down

Expected:

- Source A sync fails
- retry according to policy
- Source B/C continue
- freshness for A deteriorates
- API semantics reflect partial/stale state

## Scenario 2: Source A returns the same transaction 10 times

Expected:

- one canonical transaction
- duplicate attempts recorded in metrics
- no data duplication

## Scenario 3: Source B returns malformed transaction

Expected:

- malformed record quarantined
- valid records continue processing
- batch does not necessarily fail

## Scenario 4: Database goes down

Expected:

- ingestion fails safely
- no false success
- retry occurs
- API returns appropriate availability error

## Scenario 5: Application crashes halfway through ingestion

Expected:

- previously committed transactions remain
- uncommitted work rolls back
- next run safely resumes
- duplicate records do not appear

## Scenario 6: Categorizer changes

Expected:

- existing categories remain explainable
- explicit reprocessing is possible
- historical data does not silently mutate unless designed to

---

# 82. Important Architectural Invariants

Write these down.

## Invariant 1

A source transaction is uniquely identified by:

```text
(sourceId, sourceTransactionId)
```

## Invariant 2

Processing the same source transaction repeatedly is idempotent.

## Invariant 3

Transactions from different currencies are never silently aggregated together.

## Invariant 4

A customer cannot access another customer's transactions.

## Invariant 5

Partial source availability cannot masquerade as complete aggregate data.

## Invariant 6

The canonical transaction model does not depend on source-specific DTOs.

## Invariant 7

Invalid source data cannot corrupt valid transactions.

## Invariant 8

Core financial transaction facts have explicit mutation semantics.

---

# 83. Architectural Decision Records

Create ADRs for important decisions.

Suggested ADRs:

```text
ADR-001: Modular Monolith
ADR-002: PostgreSQL as Canonical Transaction Store
ADR-003: Eventual Consistency
ADR-004: Source-Scoped Transaction Identity
ADR-005: Idempotent Ingestion
ADR-006: Adapter-Based Source Integration
ADR-007: Deterministic Categorization
ADR-008: Partial Results and Source Freshness
ADR-009: No Kafka Initially
ADR-010: No Redis Initially
```

Each ADR should contain:

```text
Context
Decision
Alternatives Considered
Consequences
```

---

# 84. Example ADR

## ADR-003: Eventual Consistency

### Context

Multiple independent transaction sources do not provide a shared transaction boundary. Therefore a globally consistent snapshot cannot be guaranteed.

### Decision

The aggregation platform will use eventual consistency. Each source's last successful ingestion time will be tracked. API responses may expose freshness/completeness metadata.

### Alternatives

- synchronous runtime aggregation
- distributed transaction coordination
- strong consistency across sources

### Consequences

Positive:

- source outages do not necessarily make the API unavailable
- predictable API latency
- independent ingestion retries
- simpler architecture

Negative:

- newly created transactions may not immediately appear
- consumers must understand freshness semantics

---

# 85. What Not to Build

Avoid unnecessary scope such as:

- Kubernetes
- service mesh
- five microservices
- Kafka cluster
- Redis cluster
- Elasticsearch
- GraphQL
- CQRS everywhere
- event sourcing
- machine-learning categorization
- complicated workflow engines

Unless the requirements specifically demand them.

The goal is not to demonstrate how many technologies you can deploy.

The goal is to demonstrate that you know **when not to use them**.

---

# 86. Suggested Implementation Phases

## Phase 1: Domain and assumptions

Produce:

- assumptions
- domain model
- source contracts
- consistency model
- failure model
- API outline

Do not code yet.

---

## Phase 2: Source adapters

Implement:

```text
Source A adapter
Source B adapter
Source C adapter
```

with a common abstraction.

Test each independently.

---

## Phase 3: Normalization

Implement:

```text
Source DTO
   |
   v
Canonical Transaction
```

Test:

- dates
- amounts
- currency
- direction
- merchant
- missing fields

---

## Phase 4: Persistence

Implement:

- PostgreSQL
- Flyway migrations
- transaction table
- source metadata
- indexes
- unique constraints

---

## Phase 5: Idempotent ingestion

Implement:

- source sync
- pagination
- retries
- duplicate handling
- ingestion metrics

---

## Phase 6: Categorization

Implement a deterministic categorizer.

Example:

```text
merchant pattern -> category
```

Keep it isolated behind an interface.

---

## Phase 7: Query API

Implement:

- list transactions
- filters
- pagination
- sorting
- customer authorization
- transaction details

---

## Phase 8: Aggregation API

Implement:

- total debits
- total credits
- transaction count
- category summaries
- currency-specific aggregation

---

## Phase 9: Resilience

Add:

- timeout
- retry
- backoff
- failure isolation
- source status
- quarantine

Only add circuit breakers if justified.

---

## Phase 10: Observability

Add:

- structured logging
- metrics
- source freshness
- ingestion counters
- API latency
- error rates

---

## Phase 11: Testing

Add:

- unit tests
- integration tests
- Testcontainers
- API tests
- end-to-end ingestion tests
- failure scenarios

---

## Phase 12: Documentation

Provide:

```text
README.md
ARCHITECTURE.md
API.md
ASSUMPTIONS.md
ADR/
TESTING.md
OPERATIONS.md
```

---

# 87. README Structure

The README should make the project easy to understand.

Suggested structure:

```text
# Transaction Aggregation API
## Staff Engineer-Level Design, Implementation, Deployment & Operations Guide

**Reference stack**

- Java 21+
- Spring Boot 4
- Spring Kafka
- PostgreSQL
- Flyway
- Docker / Docker Compose
- OpenAPI
- JUnit 5
- Testcontainers
- Micrometer
- OpenTelemetry
- Prometheus-compatible metrics
- Grafana-compatible dashboards

**Messaging principle:** Kafka is the default demonstration broker, but the application should be designed around a **messaging port/interface**, not Kafka-specific domain logic. RabbitMQ, Amazon SQS/SNS, Google Pub/Sub, Azure Service Bus, NATS, or another broker should be substitutable without rewriting the domain and application layers.


## Problem

## Architecture

## Key Decisions

## Assumptions

## Running Locally

## API

## Data Model

## Ingestion

## Failure Handling

## Testing

## Observability

## Future Evolution
```

---

# 88. Architecture Documentation

Include diagrams for:

1. high-level architecture
2. ingestion flow
3. API flow
4. data model
5. failure handling
6. deployment model

Prefer diagrams that explain decisions rather than decorative diagrams.

---

# 89. Staff-Level Interview Narrative

A strong discussion can follow this order:

### Step 1

Clarify requirements.

### Step 2

Define assumptions.

### Step 3

Define consistency.

### Step 4

Define transaction identity.

### Step 5

Define canonical data model.

### Step 6

Define ingestion semantics.

### Step 7

Define failure semantics.

### Step 8

Define categorization.

### Step 9

Define API.

### Step 10

Define storage and indexing.

### Step 11

Discuss scale.

### Step 12

Discuss observability.

### Step 13

Discuss security.

### Step 14

Discuss future evolution.

This is much stronger than immediately drawing a microservices diagram.

---

# 90. Questions to Ask the Interviewer

If this is a real interview, ask:

## Data

- What does each source actually expose?
- Are source transaction IDs globally unique?
- Do sources expose customer/account identifiers?
- Can a source transaction be updated?
- Can transactions be deleted?
- Are source records ordered?
- Are duplicate records possible?

## Consistency

- How fresh does transaction data need to be?
- Is eventual consistency acceptable?
- Is partial data acceptable?

## Scale

- How many customers?
- Transactions per customer?
- Transactions per day?
- Expected API QPS?
- Expected ingestion frequency?
- Expected data retention?

## Categorization

- Are categories predefined?
- Are categorization rules configurable?
- Is categorization deterministic?
- Can categories change historically?

## API

- Who consumes the API?
- What queries are most common?
- Are summaries required?
- Is pagination required?
- Is sorting required?

## Security

- How are customers authenticated?
- How is authorization represented?
- Is tenant isolation required?

## Operations

- What uptime is expected?
- Are source outages common?
- Are source rate limits present?
- Is auditability required?

---

# 91. Questions You Should Answer Yourself

Even if the interviewer does not provide them, document assumptions.

Example:

```text
Assumption:
Each source provides a stable source-specific transaction ID.

Assumption:
The aggregation platform does not own the source systems.

Assumption:
Source data is eventually consistent.

Assumption:
Historical transactions should remain queryable.

Assumption:
Transactions may be received more than once.

Assumption:
Different currencies must not be aggregated without explicit conversion.

Assumption:
Categorization is deterministic for the initial implementation.
```

Clearly label assumptions.

Do not disguise assumptions as requirements.

---

# 92. Scaling Thought Experiment

Imagine:

```text
10,000 customers
1,000 transactions/customer/month
```

That is:

```text
10 million transactions/month
```

Still very manageable for PostgreSQL with sensible modelling and indexing.

Now:

```text
100 million transactions/month
```

You need to revisit:

- partitioning
- ingestion throughput
- batch size
- indexes
- retention
- read replicas
- aggregation strategy
- storage architecture

This is where architecture should evolve based on evidence.

---

# 93. High-Scale Evolution

Potential future architecture:

```text
                    Sources
                       |
                       v
                 Ingestion Layer
                       |
                       v
                    Kafka
                       |
          +------------+------------+
          |            |            |
          v            v            v
    Normalization  Categorizer   Audit
          |
          v
   Transaction Store
          |
     +----+----+
     |         |
     v         v
 Query API   Aggregation
             Processor
                 |
                 v
             Read Model
```

This architecture should be presented as an evolution path, not the default implementation.

---

# 94. The Core Staff Engineer Principle

The most important idea throughout this project is:

> **Do not optimize the architecture for hypothetical scale. Optimize it for explicit guarantees and known workloads, while preserving clean evolution paths.**

A senior engineer can build a service.

A Staff Engineer should be able to explain:

- what the system guarantees
- what it does not guarantee
- why those guarantees exist
- where each guarantee is enforced
- how the system behaves when things fail
- what assumptions make the design valid
- when the architecture should change

---

# 95. Final Recommended Position

The recommended initial architecture is:

```text
                    ┌────────────────────┐
                    │    REST API        │
                    └─────────┬──────────┘
                              │
                    ┌─────────▼──────────┐
                    │ Application Layer  │
                    └─────────┬──────────┘
                              │
                    ┌─────────▼──────────┐
                    │ Transaction Domain │
                    └─────────┬──────────┘
                              │
                    ┌─────────▼──────────┐
                    │    PostgreSQL      │
                    └────────────────────┘


       ┌─────────────────────────────────────┐
       │          INGESTION PIPELINE         │
       └─────────────────────────────────────┘

 Source A ──┐
 Source B ──┼──> Adapters
 Source C ──┘       |
                    v
              Normalization
                    |
                    v
              Idempotency
                    |
                    v
              Categorization
                    |
                    v
              PostgreSQL
```

With these explicit properties:

```text
Architecture       Modular monolith
Storage             PostgreSQL
Consistency         Eventual
Ingestion           Idempotent
Identity            Source-scoped + canonical
Categorization      Deterministic + versionable
Source isolation    Adapter pattern
Failures            Isolated and observable
Partial results     Explicitly represented
API                 Query-oriented
Pagination          Deterministic
Money               BigDecimal / safe monetary representation
Currencies          Never silently mixed
Security             Customer-scoped authorization
Observability        Metrics + structured logs
Messaging            Kafka via replaceable messaging port
Caching              Not initially required
Microservices        Not initially required
```

---

# 96. The Design Review Checklist

Before considering the architecture complete, ask:

## Requirements

- [ ] Are assumptions documented?
- [ ] Is "aggregation" explicitly defined?
- [ ] Is the consistency model explicit?
- [ ] Is freshness defined?
- [ ] Is partial availability defined?

## Domain

- [ ] Is transaction identity defined?
- [ ] Is source provenance retained?
- [ ] Is money represented safely?
- [ ] Is currency handled correctly?
- [ ] Is categorization isolated?

## Ingestion

- [ ] Is ingestion idempotent?
- [ ] Are retries bounded?
- [ ] Are failures classified?
- [ ] Are malformed records quarantined?
- [ ] Is incremental ingestion supported where possible?

## API

- [ ] Are endpoints consumer-oriented?
- [ ] Is pagination deterministic?
- [ ] Are filters bounded?
- [ ] Are aggregate semantics clear?
- [ ] Are error responses consistent?

## Security

- [ ] Is authentication defined?
- [ ] Is authorization enforced?
- [ ] Is customer isolation guaranteed?
- [ ] Are sensitive values excluded from logs?

## Operations

- [ ] Can source freshness be observed?
- [ ] Can failed ingestion be diagnosed?
- [ ] Are duplicate records measurable?
- [ ] Are API errors measurable?
- [ ] Is database health observable?

## Testing

- [ ] Unit tests
- [ ] Integration tests
- [ ] Database constraint tests
- [ ] API tests
- [ ] End-to-end ingestion tests
- [ ] Failure tests
- [ ] Idempotency tests
- [ ] Authorization tests

## Evolution

- [ ] Can another source be added without modifying the domain?
- [ ] Can categorization evolve?
- [ ] Can ingestion scale independently later?
- [ ] Can Kafka be introduced later if justified?
- [ ] Can materialized aggregates be introduced later?
- [ ] Can the API scale horizontally?

---

# 96A. Docker Compose Deployment

The reference deployment should be runnable with one command:

```bash
docker compose up -d
```

A practical local/demo stack:

```text
┌──────────────────────────────────────────────────────────────┐
│                      Docker Compose                           │
│                                                              │
│  ┌────────────┐      ┌────────────┐      ┌──────────────┐   │
│  │ API / App  │─────>│ PostgreSQL │      │    Kafka     │   │
│  │ Spring     │      └────────────┘      └──────┬───────┘   │
│  │ Boot 4     │                                  │           │
│  └─────┬──────┘                                  │           │
│        │                                         │           │
│        └─────────────────────────────────────────┘           │
│                                                              │
│  ┌────────────┐      ┌──────────────┐                       │
│  │ Prometheus │─────>│   Grafana    │                       │
│  └────────────┘      └──────────────┘                       │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

For Kafka, use a container image/configuration that keeps the local setup straightforward.

Do not make the demo depend on a large Kubernetes installation.

The objective is:

```bash
git clone ...
cd transaction-aggregation
docker compose up -d
```

followed by:

```bash
curl http://localhost:8080/actuator/health
```

and the API should be usable.

---

# 96B. Docker Compose Principles

The Compose environment should provide:

- deterministic service names
- health checks
- persistent PostgreSQL storage
- Kafka persistence where useful
- application configuration via environment variables
- explicit network
- restart policies
- resource limits where appropriate
- no hard-coded secrets
- predictable startup dependencies

Do not rely solely on:

```yaml
depends_on:
  - postgres
```

Application startup ordering is not the same thing as dependency readiness.

Use health checks where practical.

---

# 96C. Example Service Topology

Conceptually:

```text
services:

  transaction-api
       |
       +--> postgres
       |
       +--> kafka

  postgres

  kafka

  prometheus
       |
       +--> transaction-api

  grafana
       |
       +--> prometheus
```

Kafka UI can be added for demonstration purposes:

```text
kafka-ui
    |
    v
  Kafka
```

This is useful during a technical walkthrough because you can show:

- topics
- partitions
- consumer groups
- offsets
- messages

---

# 96D. Container Image

Build a small production-oriented image.

Prefer a multi-stage build:

```text
Stage 1
Maven / build tooling
        |
        v
Spring Boot JAR
        |
        v
Stage 2
Minimal Java runtime
```

Do not run the application as root.

The runtime image should contain only what the application requires.

---

# 96E. Container Security

The application container should:

- run as a non-root user
- use a read-only filesystem where practical
- drop unnecessary Linux capabilities
- avoid privileged mode
- receive secrets through environment/secret mechanisms
- expose only the application port
- avoid mounting the host Docker socket

For the demo, some simplifications are acceptable, but document them.

---

# 96F. Configuration

Use Spring configuration with environment overrides.

Example:

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD

SPRING_KAFKA_BOOTSTRAP_SERVERS

APP_INGESTION_INTERVAL
APP_SOURCE_TIMEOUT
APP_SOURCE_RETRY_COUNT
```

Do not commit:

```text
password=production-password
apiKey=...
secret=...
```

Use:

```text
.env
```

locally and a proper secret mechanism in real deployment.

---

# 96G. Secrets

Separate:

```text
configuration
```

from:

```text
secrets
```

Configuration:

```text
database host
Kafka bootstrap server
feature flags
timeouts
```

Secrets:

```text
database password
API credentials
signing keys
source authentication tokens
```

For Docker Compose demonstration, `.env` or Docker secrets can be used.

For production, use:

- AWS Secrets Manager
- Azure Key Vault
- GCP Secret Manager
- Vault
- Kubernetes Secrets backed by an external secret manager

Do not hard-code secrets into images.

---

# 96H. PostgreSQL Deployment

PostgreSQL should use a persistent volume.

Do not rely on the container filesystem for database durability.

Example conceptual mapping:

```text
postgres container
      |
      v
postgres-data volume
```

Production PostgreSQL should generally be managed separately from the application deployment unless there is a compelling operational reason to self-host it.

For a demo:

```text
Docker Compose PostgreSQL
```

is ideal.

For production:

```text
Managed PostgreSQL
```

is usually preferable.

---

# 96I. Kafka Deployment

For demonstration:

```text
Docker Compose Kafka
```

is appropriate.

For production, Kafka should generally be treated as infrastructure rather than embedded casually inside an application host.

Consider:

- managed Kafka
- MSK
- Confluent Cloud
- Aiven
- Redpanda
- self-managed Kafka cluster

The application should not care which one is used.

---

# 96J. Database Migrations

Flyway migrations should run as part of application startup or as a dedicated deployment step.

Recommended:

```text
V1__initial_schema.sql
V2__add_source_sync.sql
V3__add_category_version.sql
```

Migrations should be:

- forward-only
- reviewable
- deterministic
- idempotent where appropriate
- tested

Do not manually modify production schemas.

---

# 96K. Deployment Environments

Use at least:

```text
local
demo
production
```

Configuration should vary by environment.

Example:

```text
local:
  Docker Compose
  local PostgreSQL
  local Kafka

demo:
  Docker Compose or small VPS
  persistent volumes
  TLS
  restricted network

production:
  managed PostgreSQL
  managed Kafka
  multiple application instances
  load balancer
  centralized secrets
  monitoring
```

---

# 96L. Demo VPS Deployment

If the assessment requires a deployed demonstration, a small Linux VPS is sufficient.

A practical topology:

```text
Internet
   |
   v
Nginx / Caddy
   |
   v
Spring Boot API
   |
   +---- PostgreSQL
   |
   +---- Kafka
```

Prometheus/Grafana can run privately or behind authentication.

Do not expose:

```text
5432 PostgreSQL
9092 Kafka
```

directly to the public internet.

Only expose the HTTP/HTTPS entry point.

---

# 96M. TLS

The public API should use HTTPS.

For a demo:

```text
https://api.example.com
```

Terminate TLS at:

- Nginx
- Caddy
- cloud load balancer
- ingress controller

Internally, Docker Compose can use plain HTTP unless internal encryption is explicitly required.

For production, evaluate encryption between services based on threat model.

---

# 96N. Reverse Proxy

A reverse proxy can provide:

- TLS termination
- request limits
- connection limits
- compression
- security headers
- access logs
- routing

Example:

```text
Client
  |
 HTTPS
  |
  v
Nginx
  |
  v
Spring Boot :8080
```

The application should still enforce authentication and authorization.

A reverse proxy is not an authorization layer.

---

# 96O. Health Endpoints

Use Spring Boot Actuator.

At minimum:

```text
/actuator/health
```

Potentially:

```text
/actuator/metrics
/actuator/prometheus
```

Do not expose all actuator endpoints publicly.

Prefer:

```text
public:
  health

internal:
  metrics
  prometheus
```

or expose metrics through an internal network.

---

# 96P. Readiness and Liveness

Distinguish:

### Liveness

> Is the application process alive?

### Readiness

> Can the application safely receive traffic?

For example:

```text
Kafka unavailable
PostgreSQL unavailable
```

may affect readiness differently depending on the endpoint's actual dependencies.

Do not blindly mark every external dependency as a liveness dependency.

A database outage should not necessarily cause an infinite restart loop.

---

# 96Q. Production Scaling

The API should be stateless.

Scale horizontally:

```text
                  Load Balancer
                  /     |     \
                 /      |      \
             API-1    API-2    API-3
                \       |       /
                 \      |      /
                  PostgreSQL
```

Kafka consumers can scale independently:

```text
Topic
 |
 +--- Consumer 1
 +--- Consumer 2
 +--- Consumer 3
```

The number of active consumers in a consumer group is bounded by the number of partitions.

Therefore:

```text
partitions >= desired maximum parallel consumers
```

if independent parallelism is required.

---

# 96R. Kafka Scaling

Kafka scaling involves:

- topic partitions
- consumer groups
- consumer instances
- partition keys
- throughput
- retention
- replication

Example:

```text
transactions.normalized
       |
       +-- partition 0 -> consumer A
       +-- partition 1 -> consumer B
       +-- partition 2 -> consumer C
```

If all messages for a customer must remain ordered, partition by customer ID.

If ordering is not required, use a key appropriate to load distribution.

---

# 96S. PostgreSQL Scaling

Start with one PostgreSQL instance.

If workload grows:

```text
Application
    |
    +---- Primary
    |
    +---- Read Replica
```

Potential next steps:

- connection pooling
- query optimization
- indexes
- partitioning
- read replicas
- archival
- managed PostgreSQL
- sharding only as a much later option

Do not reach for sharding before proving it is necessary.

---

# 96T. Connection Pooling

Use HikariCP through Spring Boot.

Monitor:

```text
active connections
idle connections
pending requests
connection acquisition time
```

Do not simply increase pool size to solve latency.

Too many database connections can reduce database performance.

The correct pool size depends on:

- CPU
- query latency
- workload
- database capacity
- number of application instances

---

# 96U. Rate Limiting

Protect both:

```text
our API
```

and:

```text
upstream sources
```

API rate limiting can be implemented at:

- reverse proxy
- API gateway
- application layer

Source rate limiting should be respected by adapters.

If Source A permits 10 requests/sec, the ingestion scheduler must not accidentally create 100 requests/sec by scaling horizontally.

---

# 96V. Timeouts

Every outbound source call should have an explicit timeout.

Never rely on an infinite default.

Define:

```text
connection timeout
read timeout
overall request timeout
```

Timeouts should be configurable.

A downstream source should not be able to consume application threads indefinitely.

---

# 96W. Bulkheads

Consider isolating source workloads.

For example:

```text
Source A worker pool
Source B worker pool
Source C worker pool
```

or bounded asynchronous execution.

A slow Source A should not consume all resources required by Source B.

This is the bulkhead principle.

Do not over-engineer the first version, but understand the failure mode.

---

# 96X. Observability Stack

A demo deployment can use:

```text
Spring Boot
    |
    +--> Micrometer
           |
           v
       Prometheus
           |
           v
        Grafana
```

Metrics to expose:

```text
HTTP request rate
HTTP error rate
HTTP latency
Kafka consumer lag
Kafka processing failures
source sync failures
source freshness
transactions processed
duplicates detected
quarantined transactions
database connection pool
JVM memory
JVM GC
```

---

# 96Y. Kafka Consumer Lag

Consumer lag is one of the most important operational signals.

Conceptually:

```text
Producer offset: 10000
Consumer offset: 9700

Lag = 300
```

A growing lag indicates:

- consumers cannot keep up
- downstream processing is slow
- partitions are imbalanced
- a dependency is failing

Track it in monitoring.

---

# 96Z. Alerting

Useful alerts:

```text
API error rate > threshold
API latency > threshold
source freshness > SLA
Kafka consumer lag > threshold
database connection pool exhausted
ingestion failure rate > threshold
quarantine rate > threshold
disk usage > threshold
```

Avoid alerting on every small transient failure.

Alerts should represent conditions requiring action.

---

# 96AA. Logging Strategy

Use structured JSON logging in deployed environments.

Include:

```text
timestamp
level
service
traceId
spanId
event
source
transactionId
eventId
```

Never log sensitive information unnecessarily.

Example:

```json
{
  "level": "INFO",
  "event": "transaction_processed",
  "transactionId": "txn_123",
  "source": "SOURCE_A",
  "eventId": "evt_456"
}
```

---

# 96AB. Correlation IDs

Requests should carry or receive a correlation ID.

Example:

```text
X-Correlation-ID
```

Propagate it through application logs and, where appropriate, messaging metadata.

For distributed tracing, use OpenTelemetry trace context rather than inventing a parallel tracing system.

---

# 96AC. Disaster Recovery

Production architecture should define:

### PostgreSQL

- backups
- point-in-time recovery
- backup verification
- retention
- recovery time objective
- recovery point objective

### Kafka

Depending on deployment:

- replication
- retention
- backup strategy where required
- ability to rebuild downstream state

### Application

- immutable container images
- infrastructure/configuration as code
- reproducible deployment

---

# 96AD. Backup Is Not Recovery

A Staff Engineer should explicitly distinguish:

> "We have backups."

from:

> "We have tested recovery."

A backup strategy should include periodic restore tests.

Measure:

```text
RPO
RTO
```

Do not claim a particular RPO/RTO unless it has actually been designed and tested.

---

# 96AE. CI/CD

A sensible pipeline:

```text
Commit
  |
  v
Compile
  |
  v
Unit Tests
  |
  v
Integration Tests
  |
  v
Static Analysis
  |
  v
Build Container
  |
  v
Container Security Scan
  |
  v
Publish Image
  |
  v
Deploy Demo
```

Use immutable image tags:

```text
transaction-api:1.4.0
transaction-api:git-abc123
```

Avoid relying exclusively on:

```text
latest
```

for deployed environments.

---

# 96AF. Deployment Strategy

For a small demo:

```text
docker compose pull
docker compose up -d
```

For production, consider:

- rolling deployments
- blue/green
- canary
- managed container platforms
- Kubernetes only if justified

The API should remain backward-compatible during deployment.

---

# 96AG. Database Deployment Safety

Do not assume application rollback means database rollback.

Example:

```text
Application v2
    |
    v
Database migration V5
```

If application v2 fails and you roll back to v1, V5 may still exist.

Use backward-compatible migrations where practical:

```text
Expand
  |
  v
Deploy compatible application
  |
  v
Migrate data
  |
  v
Contract
```

This is particularly important for zero-downtime deployments.

---

# 96AH. Graceful Shutdown

Spring Boot should gracefully stop:

- HTTP traffic
- Kafka consumers
- scheduled ingestion
- database work

Do not kill consumers mid-processing without allowing the application to commit/rollback safely.

This reduces duplicate processing and partial work.

Because the system is at-least-once, graceful shutdown improves behavior but does not remove the need for idempotency.

---

# 96AI. Security Architecture

A production-ready security model should include:

```text
Internet
   |
   v
TLS
   |
   v
Reverse Proxy / Gateway
   |
   v
Spring Security
   |
   +--> Authentication
   |
   +--> Authorization
   |
   v
Application
```

Potential authentication options:

- OAuth2/OIDC
- JWT
- corporate identity provider

The application should validate:

- token signature
- issuer
- audience
- expiry
- scopes/roles

Do not trust a user ID supplied only in the URL.

---

# 96AJ. Authorization Model

A simple model:

```text
ROLE_CUSTOMER
ROLE_SUPPORT
ROLE_ADMIN
```

But permissions should map to actual operations.

Examples:

```text
transactions:read
transactions:admin
sources:read
sources:sync
```

For customer access:

```text
principal -> authorized customer(s) -> transaction query
```

This should be enforced server-side.

---

# 96AK. Data Access Security

Do not accidentally create endpoints such as:

```http
GET /v1/transactions?customerId=anything
```

where the caller can freely choose the customer.

Prefer authorization-aware endpoints:

```http
GET /v1/customers/{customerId}/transactions
```

and validate that the authenticated principal is allowed to access that customer.

For more advanced multi-tenant deployments, PostgreSQL Row-Level Security can be considered, but it should not be added merely for complexity.

---

# 96AL. Dependency Security

Keep dependencies current.

Automate:

- dependency vulnerability scanning
- container scanning
- secret scanning
- static analysis

Examples:

```text
OWASP Dependency Check
Trivy
GitHub Dependabot
Snyk
Semgrep
```

Use whichever tooling fits the environment.

Do not introduce five scanners that all report the same issue.


# 97. Final Assessment Philosophy

Do not try to impress the reviewer with infrastructure.

Instead, make the reviewer think:

> "This engineer understands where the difficult parts actually are."

The difficult parts are not:

```text
Spring Boot
REST controllers
PostgreSQL
Docker
```

The difficult parts are:

```text
What is a transaction?

What does aggregate mean?

What makes two transactions the same?

What happens when sources disagree?

How fresh is the data?

What happens when a source disappears?

How do we avoid duplicate ingestion?

How do we know where a value came from?

Can we explain why a transaction has a category?

What does the API promise?

What does it explicitly not promise?

Where do we enforce correctness?

How does this architecture evolve when the workload changes?
```

Those are the questions that should drive the implementation.

---

# 98. Recommended Next Step

Before writing production code, produce these five artifacts:

```text
01-assumptions.md
02-domain-model.md
03-architecture.md
04-api-contract.md
05-architecture-decisions/
```

Then challenge them with failure scenarios.

Only after those are stable should implementation begin.

The implementation should be the consequence of the design, not the thing that determines the design.


---

# 99. Example Docker Compose Blueprint

A real repository should contain something approximately like:

```text
docker-compose.yml
docker-compose.demo.yml
.env.example

Dockerfile

infrastructure/
├── postgres/
├── kafka/
├── prometheus/
└── grafana/
```

A conceptual Compose file:

```yaml
services:

  postgres:
    image: postgres:latest
    environment:
      POSTGRES_DB: transactions
      POSTGRES_USER: app
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U app -d transactions"]
      interval: 10s
      timeout: 5s
      retries: 5

  kafka:
    image: <chosen-kafka-image>
    # Configure listener and persistence according to
    # the selected Kafka distribution.

  transaction-api:
    image: transaction-api:${IMAGE_TAG:-local}
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/transactions
      SPRING_DATASOURCE_USERNAME: app
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD}
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    depends_on:
      postgres:
        condition: service_healthy
      kafka:
        condition: service_started

  prometheus:
    image: prom/prometheus:<pinned-version>

  grafana:
    image: grafana/grafana:<pinned-version>

volumes:
  postgres_data:
```

The exact Kafka image and listener configuration should be selected deliberately because Kafka distributions and versions have different local-development defaults.

Pin image versions for reproducibility rather than using `latest` in the committed Compose configuration.

---

# 100. Production vs Demo Boundary

The project should explicitly distinguish what Docker Compose demonstrates from what production requires.

## Demo

```text
Docker Compose
PostgreSQL container
Kafka container
Spring Boot container
Prometheus
Grafana
Optional Kafka UI
```

## Production

```text
Managed PostgreSQL
Managed Kafka
Multiple API instances
Multiple Kafka consumers
Load balancer
TLS
Secret manager
Centralized logging
Monitoring
Alerting
Backups
Disaster recovery
Automated deployment
```

This distinction is important.

The goal is not to pretend that Docker Compose is a production orchestration platform.

The goal is to make the architecture **locally reproducible and demonstrable** while preserving a credible production evolution path.

---

# 101. Final Staff Engineer Review

Before presenting the system, be prepared to answer:

### Architecture

- Why a modular monolith?
- Why Kafka?
- Why PostgreSQL?
- Why not microservices?
- How would RabbitMQ replace Kafka?
- What would cause you to introduce another service?

### Data

- What is transaction identity?
- How do you prevent duplicates?
- How do you handle source conflicts?
- How do you handle currency?
- Can transactions change after ingestion?
- How do you reproduce historical categorization?

### Messaging

- What delivery guarantee do you provide?
- How do you handle duplicate events?
- What happens when a consumer crashes?
- How do you handle poison messages?
- Why this partition key?
- How do you monitor consumer lag?
- How would you replay events?

### Resilience

- What happens when Source A is down?
- What happens when Kafka is down?
- What happens when PostgreSQL is down?
- What happens when a source sends corrupt data?
- What happens if the application crashes halfway through ingestion?

### API

- What does the API promise?
- What does eventual consistency mean to consumers?
- How do you represent partial results?
- How does pagination remain stable?

### Security

- How is customer isolation enforced?
- What happens if someone changes the customer ID in the URL?
- What is logged?
- Where are secrets stored?
- How are APIs protected?

### Operations

- How do you know a source is stale?
- How do you know Kafka is falling behind?
- How do you know categorization is failing?
- How do you diagnose a slow API request?
- How do you recover PostgreSQL?

### Scaling

- What happens at 10x traffic?
- What happens at 100x traffic?
- What scales independently?
- When would PostgreSQL stop being sufficient?
- When would you introduce materialized aggregates?
- When would Kafka need more partitions?

If you can answer these questions clearly, the project becomes much more than a CRUD implementation.

