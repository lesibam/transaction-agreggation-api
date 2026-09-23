# ADR 001: Modular Monolith Architecture

## Status
Accepted

## Context
The system needs to aggregate data from multiple sources and expose it via an API. There is a temptation to build this as a set of microservices (e.g., Ingestion Service, Categorization Service, Query Service). At the current scope the team is small, the domain boundaries are still being discovered, and operational overhead matters more than independent deployability of each slice.

## Decision
We will implement the system as a **Modular Monolith** — a single Spring Boot deployable with strict package boundaries: `api`, `application` (including `application.dto`), `domain`, `infrastructure`, `security`, `config`.

## Alternatives Considered
- **Microservices from day one** (separate ingestion, categorization, and query services): rejected — network partitions, distributed tracing, and multiple deployment pipelines would dominate the effort before the domain is stable; nothing in the problem requires independent scaling of individual slices yet.
- **Serverless/function-per-step pipeline**: rejected — awkward fit for a stateful query API with keyset pagination and a long-lived Kafka consumer; cold-start and local-development complexity add no value here.
- **Unstructured monolith (no enforced boundaries)**: rejected — without package-level discipline the "modular" benefit disappears and future extraction becomes a rewrite.
- **Layered monolith with a single domain package** (no domain/application split): rejected — the DDD-inspired split keeps business rules free of framework concerns and makes the messaging port and categorizer ports extractable.

## Rationale
1. **Operational Simplicity**: For the current scope, the overhead of managing multiple deployments, networks, and distributed tracing outweighs the benefits.
2. **Developer Velocity**: Refactoring domain boundaries is significantly easier within a single codebase.
3. **Performance**: In-process communication eliminates network latency between the ingestion pipeline and the persistence layer.
4. **Evolution Path**: By strictly enforcing boundaries via Java packages, any module (e.g., ingestion) can be extracted into a separate service later if scale demands it.

## Consequences
- We must be disciplined about package dependencies (e.g., `domain` must not depend on `infrastructure`); this is enforced by review and architecture tests rather than the compiler alone.
- Deployment is all-or-nothing, but horizontal scaling of the entire monolith is simple (Helm `replicaCount`), with ShedLock preventing concurrent duplicate ingestion cycles across instances.
- Scaling is coarse-grained: we cannot scale only the categorizer — acceptable at current volumes, revisited if one slice dominates load.
