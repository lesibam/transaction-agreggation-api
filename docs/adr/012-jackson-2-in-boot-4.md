# ADR 012: Why Jackson 2 Still Exists in a Boot 4 App

## Status
Accepted

## Context
Spring Boot 4 auto-configures Jackson 3 (`tools.jackson.*`) for HTTP message conversion. Two things production code still needs a *Jackson 2* (`com.fasterxml.jackson.*`) `ObjectMapper` for, neither of which is Boot's own HTTP layer:

1. **`SecurityConfig`** writes RFC 7807 problem-detail bodies for authentication failures (401/403) from inside a Spring Security entry point — a context that runs before/outside the normal Spring MVC message-converter pipeline Jackson 3 auto-configures for.
2. **Kafka (de)serialization** — `spring-kafka`'s `JsonSerializer`/`JsonDeserializer` (`org.springframework.kafka.support.serializer`) are built against Jackson 2's `ObjectMapper`, not Jackson 3. `KafkaConsumerConfig` and `KafkaProducerConfig` both need one to (de)serialize `TransactionIngestedEvent`, including its `OffsetDateTime` fields.

A first pass at this left three independently-constructed Jackson 2 mappers: one shared bean (`Jackson2Config`) actually used by `SecurityConfig` and `KafkaConsumerConfig`, and a *second*, hand-built one inlined directly in `KafkaProducerConfig` — same `JavaTimeModule` registration, written twice, free to drift out of sync with no compiler or test signal if someone changed one and not the other.

## Decision
**Keep exactly one Jackson 2 `ObjectMapper` bean** (`Jackson2Config.jackson2ObjectMapper()`), injected by type into every Jackson-2-shaped call site: `SecurityConfig`, `KafkaConsumerConfig`, and `KafkaProducerConfig`. Autowiring by type is unambiguous here specifically *because* Jackson 3 lives under a different Java package (`tools.jackson.databind.ObjectMapper`) — there is only one bean of type `com.fasterxml.jackson.databind.ObjectMapper` in the context, so no `@Qualifier`/`@Primary` juggling is needed.

## Alternatives Considered
- **Migrate Kafka messaging to Jackson 3**: rejected for now — `spring-kafka`'s serializer support for Jackson 3 was still maturing at the Boot 4.1.1 / Spring Kafka version pinned in `pom.xml`; forcing it would trade a well-understood, working Jackson 2 path for an unproven one, for a cosmetic consistency win.
- **Two independently-built Jackson 2 mappers** (the state before this ADR): rejected — this is exactly the "config that can silently drift" pattern this codebase avoids elsewhere (see the `application.yml` Kafka properties cleanup, review finding M-01); two mappers meant to behave identically is a bug waiting for someone to change one.
- **Don't register `JavaTimeModule` at all, handle `OffsetDateTime` some other way**: rejected — `TransactionIngestedEvent` carries `OffsetDateTime` fields; without it, Jackson 2 fails to serialize them by default.

## Rationale
1. **One mapper, one configuration, one place to change it.** If java.time handling needs to change (a new module, different date formatting), there is now exactly one bean to update, and everything downstream inherits it automatically.
2. **This is a known, temporary seam, not permanent architecture.** Jackson 2 exists here because of *where* Boot 4's Jackson 3 auto-configuration doesn't reach (Security's early-pipeline error responses, Spring Kafka's serializer contract) — not because Jackson 3 was rejected on its merits. The next engineer should not "clean this up" by deleting `Jackson2Config` without first checking whether spring-kafka's Jackson 3 support has matured and whether `SecurityConfig`'s problem-detail writer can move onto the standard MVC pipeline.

## Consequences
**Positive**
- A single source of truth for Jackson 2 behavior; the class-level Javadoc on `Jackson2Config` and this ADR both explain *why* it exists, so it reads as a deliberate seam rather than debris from an incomplete migration.

**Negative**
- The app genuinely runs two JSON stacks side by side (Jackson 3 for HTTP via Boot's auto-config, Jackson 2 for security error bodies and Kafka) — a real, if contained, source of behavioral asymmetry between the two if they're ever configured to treat some edge case (e.g., an unknown enum value) differently. Worth a note if either stack's config changes.
- Revisit this ADR when upgrading `spring-kafka` versions — the situation this ADR describes (spring-kafka on Jackson 2) is the one condition under which deleting `Jackson2Config` becomes safe.
