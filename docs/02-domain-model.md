# Artifact 02: Domain Model - Transact API

This document defines the canonical domain model for the Transaction Aggregation system. The goal is to provide a unified representation of financial data regardless of the source.

## 1. Core Entities

### 1.1 Customer
The top-level entity representing the owner of the financial data (tenant root).
- **ID**: UUID (Internal canonical ID).
- **External ID**: String (ID from the identity provider; unique).
- **Attributes**: Name, Email, TenantID.
- **Audit**: `created_at`, `updated_at`, `created_by`, `updated_by`.

### 1.2 Account
A financial account owned by a customer.
- **ID**: UUID.
- **CustomerID**: UUID (FK, indexed).
- **AccountType**: Enum (SAVINGS, CHECKING, CREDIT, etc.).
- **Currency**: ISO-4217 Code (e.g., "ZAR").
- **Source**: The original source that provided this account (`source_provider`).
- **Audit**: standard audit fields.

### 1.3 Transaction (Canonical)
The primary domain object representing a financial movement. Persisted in the `transactions` table.
- **ID**: UUID (Canonical internal ID).
- **CustomerID**: UUID — the owning customer is carried on the transaction itself so row-level ownership validation and tenant-filtered queries do not require a join through `accounts`.
- **AccountID**: UUID (FK, indexed).
- **Amount**: `BigDecimal` (`DECIMAL(19, 4)`; absolute value — sign is carried by `direction`).
- **Currency**: ISO-4217 Code.
- **Direction**: Enum (`DEBIT`, `CREDIT`).
- **TransactionDate**: OffsetDateTime (When the transaction occurred; part of the keyset sort key).
- **PostedAt**: OffsetDateTime (When it was posted by the source).
- **Description**: String (Raw description).
- **MerchantName**: String (Normalized merchant name when present).
- **Category**: `Category` value object (see 2.2) — persisted as `category_code`, `category_version`, and `rule_id`.
- **Source Provenance**: `source_id`, `source_transaction_id`, `ingested_at` (see 2.3).
- **NormalizationVersion**: String — which normalization logic version produced this row (see 2.4).
- **Audit**: `created_at`, `updated_at`, `created_by`, `updated_by`.

## 2. Value Objects

### 2.1 Merchant (representation)
- **Name**: String (Normalized name; stored as `merchant_name`).
- **RawName / Description**: Original text from the source retained on the transaction for re-processing.

### 2.2 Category
- **Code**: String (Canonical code, e.g., "GROCERIES", "FOOD_DELIVERY", "UNCATEGORIZED").
- **Version**: Integer — the categorizer rule-set version (`category_version`) used when this category was assigned; enables tracking how categorization logic evolves.
- **RuleId**: String — the specific rule that fired (e.g., `MERCHANT_MATCH_WOOLWORTHS`, `DEFAULT_UNCATEGORIZED`). **Persisted** on the transaction row for explainability and audit.

### 2.3 TransactionSource (Provenance)
Crucial for auditability and deduplication.
- **SourceId**: String (e.g., `SOURCE_A`, `SOURCE_B`, `SOURCE_C`).
- **SourceTransactionId**: String (The ID provided by the source, unique only within that source).
- **IngestedAt**: OffsetDateTime.

### 2.4 NormalizationVersion
- **Value**: String (e.g., `"1"` — `CanonicalTransaction.NORMALIZATION_VERSION`).
- **Purpose**: Records which adapter normalization logic version produced the canonical row, so schema evolution can be detected and a re-normalization can be scoped to affected rows.

## 3. Supporting Entities

### 3.1 SourceSyncState (`source_sync_state`)
Per-source ingestion cursor and health — the source of truth for freshness and completeness.
- **SourceId**: String (PK).
- **Cursor**: String (incremental ingestion position).
- **LastSuccessfulSync**: OffsetDateTime.
- **Status**: String — `SUCCESS` or `FAILED` (plus `INITIAL` before the first cycle).
- **FailureCount**, **LastError**, **UpdatedAt**.

### 3.2 IngestionError (`ingestion_error`)
Quarantine record for ingestion failures that must not be silently dropped.
- **ID**: UUID.
- **SourceId**: String.
- **SourceTransactionId**: String (when known).
- **ErrorType / Message**: classification (retryable vs non-retryable) and sanitized detail.
- **PayloadReference or RawPayload**: enough context to replay or diagnose.
- **OccurredAt**, **Resolved** (or equivalent) flags, and standard audit fields where applicable.

Validation failures (malformed/missing fields), missing accounts, and non-duplicate integrity failures land here; events that still fail after the consumer's bounded retries are dead-lettered to `transactions.dlq` instead of poisoning the main flow.

## 4. Domain Invariants & Rules

### 4.1 Transaction Identity
A transaction is uniquely identified by the combination of its source and its source-specific ID:
`UNIQUE(source_id, source_transaction_id)`

This pair is enforced by a database unique index — never by check-then-insert application logic — making ingestion idempotent under at-least-once delivery. The same `source_transaction_id` from two different sources remains two distinct rows.

### 4.2 Customer Ownership
Every transaction row carries `customer_id`. Row-level ownership is validated on every read: the JWT `sub` must equal the path `customerId` unless the caller is `ADMIN`.

### 4.3 Monetary Arithmetic
- **Precision**: All money is handled as `BigDecimal` with `DECIMAL(19, 4)` storage (scale applied per operation with an explicit rounding mode where needed).
- **No Conversion**: The system does not perform implicit currency conversion. Aggregates are reported per currency.

### 4.4 Categorization Pipeline
The flow for a transaction is:
`Raw Payload` → `Normalized Transaction` (adapter, `normalization_version`) → `Categorized Transaction` (consumer, `category_code` + `category_version` + `rule_id`).

Categorization is a separate, deterministic step; the persisted `ruleId` makes every assignment explainable.

### 4.5 Soft Deletes / Retention
No cascade deletes on business tables; deletion/retention policy is a future concern (see guide §70) and is not part of the current schema.

---
**Status**: Implemented
**Owner**: Domain Expert
**Date**: 2026-09-23
