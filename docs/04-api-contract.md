# Artifact 04: API Contract - Transact API

This document defines the public interface of the Transact API. All endpoints are versioned under `/v1`. The machine-readable contract lives at [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1) and is authoritative for generated clients.

## 1. General Principles
- **Format**: JSON (`application/json`); errors use `application/problem+json`.
- **Authentication**: `Authorization: Bearer <JWT>`. The JWT `sub` claim must equal the path `customerId` unless the token carries the `ADMIN` role (enforced by `CustomerAccessValidator`).
- **Pagination**: Keyset (cursor) pagination for list endpoints. Sort key: **`transaction_date DESC, id DESC`**. The opaque `cursor` encodes the last-seen sort key; passing `meta.nextCursor` back as `cursor` resumes exactly after that row. Offset pagination is not used.
- **Dates**: ISO-8601 with offset (e.g., `2026-09-23T12:00:00Z`).
- **Errors**: RFC 7807 `ProblemDetail` with `type`, `title`, `status`, `detail`, `instance`, and a `traceId` extension copied from the MDC `correlationId` (the value of the incoming/assigned `X-Correlation-ID` header).
- **Currency**: No implicit conversion. Aggregates are reported per currency.

---

## 2. Endpoints

### 2.1 Get Customer Transactions

`GET /v1/customers/{customerId}/transactions`

**Auth**: `CUSTOMER` (own `customerId` only) or `ADMIN`.

**Query parameters**:

| Param | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `limit` | integer (1–100) | `20` | Page size |
| `cursor` | string | — | Opaque keyset cursor from `meta.nextCursor` |
| `category` | string | — | Filter by canonical `category_code` (e.g. `GROCERIES`) |
| `direction` | `DEBIT` \| `CREDIT` | — | Filter by transaction direction |
| `startDate` | date-time | — | Inclusive lower bound on `transaction_date` |
| `endDate` | date-time | — | Inclusive upper bound on `transaction_date` |
| `minAmount` | number | — | Inclusive lower bound on `amount` (absolute value; sign is carried by `direction`) |
| `maxAmount` | number | — | Inclusive upper bound on `amount` (absolute value) |

**Response `200`**:
```json
{
  "data": [
    {
      "id": "txn_uuid",
      "amount": { "value": 120.50, "currency": "ZAR" },
      "direction": "DEBIT",
      "transactionDate": "2026-09-22T10:00:00Z",
      "description": "Woolworths Sandton",
      "merchant": { "name": "Woolworths" },
      "category": { "code": "GROCERIES" },
      "source": { "provider": "SOURCE_A", "transactionId": "ext_123" }
    }
  ],
  "meta": {
    "nextCursor": "opaque_cursor_or_null",
    "hasMore": true,
    "completeness": "COMPLETE",
    "freshness": {
      "status": "FRESH",
      "generatedAt": "2026-09-23T12:00:00Z",
      "sources": [
        { "source": "SOURCE_A", "lastSync": "2026-09-23T11:55:00Z", "status": "AVAILABLE" },
        { "source": "SOURCE_B", "lastSync": "2026-09-23T11:50:00Z", "status": "AVAILABLE" },
        { "source": "SOURCE_C", "lastSync": "2026-09-23T10:00:00Z", "status": "UNAVAILABLE" }
      ]
    }
  }
}
```

**Errors**: `400` invalid cursor/params, `401` missing/invalid JWT (type `…/unauthenticated`), `403` `customerId` ≠ JWT `sub` (non-admin; type `…/forbidden`, thrown by `CustomerAccessValidator`) or missing role (type `…/access-denied`, from the security filter), `500` sanitized ProblemDetail.

### 2.2 Get Transaction Summary

`GET /v1/customers/{customerId}/summary`

**Auth**: `CUSTOMER` (own `customerId` only) or `ADMIN`.

**Query parameters**:

| Param | Type | Description |
| :--- | :--- | :--- |
| `startDate` | date-time | Inclusive lower bound on `transaction_date` |
| `endDate` | date-time | Inclusive upper bound on `transaction_date` |

Aggregates are computed in SQL (`SUM` grouped by currency / category) — no in-memory reduction of full result sets, no cross-currency totals.

**Response `200`**:
```json
{
  "summaries": [
    { "currency": "ZAR", "totalDebit": 5000.00, "totalCredit": 12000.00, "netFlow": 7000.00 }
  ],
  "categoryBreakdown": [
    { "category": "FOOD_AND_DINING", "amount": 1200.00, "currency": "ZAR", "transactionCount": 3 }
  ],
  "meta": {
    "nextCursor": null,
    "hasMore": false,
    "completeness": "PARTIAL",
    "freshness": {
      "status": "STALE",
      "generatedAt": "2026-09-23T12:00:00Z",
      "sources": [
        { "source": "SOURCE_A", "lastSync": "2026-09-23T11:55:00Z", "status": "AVAILABLE" },
        { "source": "SOURCE_B", "lastSync": "2026-09-23T11:50:00Z", "status": "AVAILABLE" },
        { "source": "SOURCE_C", "lastSync": "2026-09-23T10:00:00Z", "status": "UNAVAILABLE" }
      ]
    }
  }
}
```

### 2.3 Admin: Source Sync Status

`GET /v1/admin/sources`

**Auth**: `ADMIN` only.

Returns the per-source ingestion state from `source_sync_state` — the operational view behind freshness/completeness.

**Response `200`**:
```json
{
  "sources": [
    {
      "id": "SOURCE_A",
      "name": "Source A",
      "status": "SUCCESS",
      "lastSuccessfulSync": "2026-09-23T11:55:00Z",
      "lastAttempt": "2026-09-23T11:55:00Z",
      "failureCount": 0,
      "freshnessSeconds": 300,
      "freshnessStatus": "FRESH"
    },
    {
      "id": "SOURCE_C",
      "name": "Source C",
      "status": "FAILED",
      "lastSuccessfulSync": "2026-09-23T10:00:00Z",
      "lastAttempt": "2026-09-23T12:00:00Z",
      "failureCount": 3,
      "freshnessSeconds": 7200,
      "freshnessStatus": "STALE"
    }
  ]
}
```

**Errors**: `401` (unauthenticated), `403` (authenticated but not `ADMIN` — role failure from the security filter, type `…/access-denied`).

---

## 3. Response Metadata Definitions

### 3.1 Completeness
- `COMPLETE`: Every configured source's last sync cycle finished `SUCCESS` (derived from `source_sync_state`).
- `PARTIAL`: One or more sources failed to sync or are otherwise not `SUCCESS`.

### 3.2 Freshness Status
Thresholds are configurable (defaults):
- `FRESH`: ≤ 5 minutes since last successful sync.
- `STALE`: 5–30 minutes since last successful sync.
- `VERY_STALE`: > 30 minutes since last successful sync.
- `UNKNOWN`: a source has never completed a successful sync.

The overall `freshness.status` is the worst status across sources.

---

## 4. Error Handling

All errors use `application/problem+json` (RFC 7807). Sanitized detail only — never stack traces or raw exception messages on 500s.

The `type` suffix depends on the rejection path:

| Case | Status | `type` | `title` |
| :--- | :--- | :--- | :--- |
| No/invalid JWT (security filter entry point) | 401 | `…/unauthenticated` | `Unauthenticated` |
| Authenticated but forbidden in-controller (`CustomerAccessValidator`: path `customerId` ≠ JWT `sub`, non-admin) | 403 | `…/forbidden` | `Forbidden` |
| Authenticated but role check failed at the URL level (e.g. non-admin on `/v1/admin/**`) | 403 | `…/access-denied` | `Access Denied` |

**Example 403 Forbidden (tenant isolation, thrown by `CustomerAccessValidator`)**:
```json
{
  "type": "https://api.transact.evilcorp.za/errors/forbidden",
  "title": "Forbidden",
  "status": 403,
  "detail": "Access denied: customer does not match authenticated principal",
  "instance": "/v1/customers/660f9500-f30c-52e5-b827-557766554411/transactions",
  "traceId": "f4a1b2c3-..."
}
```

**Example 401 Unauthenticated**:
```json
{
  "type": "https://api.transact.evilcorp.za/errors/unauthenticated",
  "title": "Unauthenticated",
  "status": 401,
  "detail": "Authentication is required to access this resource",
  "instance": "/v1/customers/00000000-0000-0000-0000-000000000001/transactions",
  "traceId": "9e8d7c6b-..."
}
```

---
**Status**: Implemented
**Owner**: Documentation Engineer
**Date**: 2026-09-23
