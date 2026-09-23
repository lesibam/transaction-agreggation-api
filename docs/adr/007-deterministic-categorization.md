# ADR 007: Deterministic Categorization

## Status
Accepted

## Context
Transactions arrive uncategorized (or with inconsistent source-side labels). The platform must assign canonical categories that are **reproducible** (the same input always yields the same output), **explainable** (we can say *why* a row got its category), and **evolvable** (rules change over time without corrupting historical meaning).

## Decision
Categorization is a deterministic, ordered, keyword-based rule engine (`RuleBasedCategorizer` behind the `TransactionCategorizer` port) applied by the Kafka consumer after normalization:

1. Rules are evaluated in a fixed order — first match wins (e.g., `UBER EATS` is tested before `UBER`).
2. Matching is case-insensitive "contains" against `merchantName`, falling back to `description`; no match ⇒ `UNCATEGORIZED`.
3. Every assignment **persists** `category_code`, `category_version` (the rule-set version that fired), and **`ruleId`** (the specific rule, e.g. `MERCHANT_MATCH_WOOLWORTHS` or `DEFAULT_UNCATEGORIZED`).
4. The engine itself is stateless and side-effect free — identical canonical input plus identical rule-set version always produces the identical category.

## Alternatives Considered
- **Machine-learned / probabilistic classification**: rejected — non-deterministic outputs are unacceptable for financial reporting without confidence handling, explainability infrastructure, and training data we do not have; fails the "same input ⇒ same output" requirement.
- **Trusting source-supplied category labels**: rejected — labels are inconsistent across sources and not canonical; we would import other people's taxonomy chaos.
- **Hash-bucket / rule-engine-as-data (DB-managed rules with DSL)**: considered for runtime-tunable rules — rejected *for now* as premature; in-code ordered rules are simpler to review and test. Revisited if non-engineers must edit rules.
- **Categorize at query time** (store raw, evaluate rules on read): rejected — every read pays rule evaluation, category filters/aggregates in SQL become impossible, and results could change retroactively under the reader's rule version with no record of what was in effect.
- **LLM/enrichment API calls per transaction**: rejected — nondeterministic, costly, adds external dependency to the critical write path; contradicts determinism.

## Consequences
**Positive**
- Auditability: `ruleId` + `category_version` on every row answers "why this category, under which rules."
- SQL-side filtering and aggregation on `category_code` are sound because values are stable and canonical.
- Rule changes ship as code with the rule-set version incremented — reviewable in PRs.

**Negative / accepted limitations**
- Keyword containment can misfire (no semantic understanding); mitigated by ordered, specific-first rules.
- New transactions can only ever be categorized at ingestion time — **historical rows are not retroactively re-categorized**.

### Future: immutable history + explicit reprocess
Historical categorization is treated as **immutable**: changing the rule set does **not** rewrite stored rows (their `category_version`/`ruleId` remain as-assigned). When a business need arises to refresh history, it is an **explicit reprocess** operation — a deliberate, auditable batch that re-runs the categorizer over affected rows and records the new `category_version`/`ruleId`. This operation does not exist yet; when built it must be idempotent, observable, and never implicit. Stored `ruleId` is the enabler: it lets us find rows assigned under a superseded rule without guessing.
