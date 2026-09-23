# ADR 004: Monetary Precision

## Status
Accepted

## Context
Financial systems require absolute precision. Using floating-point types (`float`, `double`) leads to rounding errors that are unacceptable in a transaction aggregation service: totals drift, reconciliation fails, and per-currency aggregates stop adding up.

## Decision
All monetary values will be represented using `java.math.BigDecimal`, persisted as `DECIMAL(19, 4)` in PostgreSQL.

## Alternatives Considered
- **`double` / `float`**: rejected — binary floating point cannot exactly represent most decimal fractions (0.1, 0.2, …); accumulated error is inevitable in sums and averages.
- **Integer minor units (cents/copies as `long`)**: considered viable (used by some payment systems) — rejected here because currencies differ in minor-unit scale, the sources deliver decimal values directly, and `BigDecimal` matches JDBC/`DECIMAL` mapping with less conversion code; minor-unit integers remain a valid future option if performance of arithmetic ever matters.
- **`java.math.BigInteger` with implicit scale conventions**: rejected — pushes scale management into every call site; easy to get wrong, no database-native mapping benefit.
- **Fixed-point types via ORM/DB-only arithmetic**: rejected — moving money handling exclusively into SQL would scatter rounding rules across queries and break the domain-layer invariants.

## Rationale
1. **Precision**: `BigDecimal` allows arbitrary-precision signed decimal numbers, eliminating rounding drift.
2. **Standardization**: It is the industry standard for financial applications in the JVM ecosystem.
3. **Control**: Explicit rounding modes (e.g., `RoundingMode.HALF_UP`) where rounding is genuinely required — never implicit.

## Implementation
- All amount fields in domain objects, entities, and DTOs use `BigDecimal`.
- Database column type: `DECIMAL(19, 4)` for `transactions.amount` and all aggregate outputs.
- All arithmetic operations specify scale and rounding mode explicitly.
- Aggregates (`SUM`) are computed in SQL and returned per currency — never summed across currencies.

## Consequences
- JSON serialization emits numbers without binary-float artifacts when values originate from `BigDecimal` with exact scales; clients should parse decimal-safe.
- Developers cannot use `+`/`*` operators on money — must use `BigDecimal` methods (a deliberate friction).
- Schema changes to money columns require immutable Flyway migrations; scale is fixed at 4 decimal places (sufficient for current currencies; exotic scales would need a migration).
