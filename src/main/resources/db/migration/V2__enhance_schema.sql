-- V2: schema enhancements per shared contract (forward-only; V1 immutable)

-- ============================================================================
-- transactions: customer ownership, rule attribution, normalization version
-- ============================================================================

ALTER TABLE transactions ADD COLUMN customer_id UUID;

UPDATE transactions t
SET customer_id = a.customer_id
FROM accounts a
WHERE t.account_id = a.id
  AND t.customer_id IS NULL;

ALTER TABLE transactions ALTER COLUMN customer_id SET NOT NULL;

ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_customer
    FOREIGN KEY (customer_id) REFERENCES customers (id);

ALTER TABLE transactions ADD COLUMN rule_id VARCHAR(100);

ALTER TABLE transactions
    ADD COLUMN normalization_version VARCHAR(20) NOT NULL DEFAULT '1';

ALTER TABLE transactions
    ADD CONSTRAINT chk_transactions_amount_positive CHECK (amount > 0);

CREATE INDEX idx_transactions_customer_date
    ON transactions (customer_id, transaction_date DESC, id DESC);

CREATE INDEX idx_transactions_account_date
    ON transactions (account_id, transaction_date DESC, id DESC);

-- ============================================================================
-- source_sync_state: audit fields, last attempted sync, display name, status guard
-- ============================================================================

ALTER TABLE source_sync_state
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE source_sync_state
    ADD COLUMN created_by VARCHAR(100) NOT NULL DEFAULT 'system';

ALTER TABLE source_sync_state
    ADD COLUMN updated_by VARCHAR(100) NOT NULL DEFAULT 'system';

ALTER TABLE source_sync_state
    ADD COLUMN last_attempted_sync TIMESTAMPTZ;

ALTER TABLE source_sync_state
    ADD COLUMN name VARCHAR(255);

ALTER TABLE source_sync_state
    ADD CONSTRAINT chk_source_sync_state_status
    CHECK (status IN ('INITIAL', 'SUCCESS', 'FAILED'));

-- ============================================================================
-- ingestion_error: dead-letter record for failed source payloads
-- ============================================================================

CREATE TABLE ingestion_error (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_id               VARCHAR(50) NOT NULL,
    source_transaction_id   VARCHAR(255),
    error_type              VARCHAR(100) NOT NULL,
    error_message           TEXT,
    raw_context             TEXT,
    occurred_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retryable               BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              VARCHAR(100) NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              VARCHAR(100) NOT NULL
);

CREATE INDEX idx_ingestion_error_source
    ON ingestion_error (source_id, occurred_at DESC);

-- ============================================================================
-- shedlock: distributed lock table for scheduled jobs
-- ============================================================================

CREATE TABLE shedlock (
    name        VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until  TIMESTAMPTZ NOT NULL,
    locked_at   TIMESTAMPTZ NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);

-- ============================================================================
-- demo seed data (idempotent)
-- ============================================================================

INSERT INTO customers (id, external_id, name, email, tenant_id, created_by, updated_by)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'ext-demo',
    'Demo Customer',
    'demo@transact.local',
    'tenant-1',
    'system',
    'system'
)
ON CONFLICT DO NOTHING;

INSERT INTO accounts (id, customer_id, account_type, currency, source_provider, created_by, updated_by)
VALUES
    ('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001', 'CHECKING', 'ZAR', 'SOURCE_A', 'system', 'system'),
    ('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000001', 'CHECKING', 'ZAR', 'SOURCE_B', 'system', 'system'),
    ('00000000-0000-0000-0000-000000000103', '00000000-0000-0000-0000-000000000001', 'CHECKING', 'ZAR', 'SOURCE_C', 'system', 'system')
ON CONFLICT DO NOTHING;
