-- V3: invariant enforcement per shared contract (forward-only; V1/V2 immutable)
--
-- transactions.direction was declared VARCHAR(10) with a comment only
-- ('DEBIT' or 'CREDIT') - not a database-level constraint. The application
-- enum (za.co.evilcorp.transact.domain.model.TransactionDirection) makes a
-- bad value unlikely via normal write paths, but invariants that matter
-- belong in the database, not just in application code that happens to
-- agree with them today (see docs/principal_engineer_review_report.md L-01).
--
-- Matches the CHECK pattern already used on source_sync_state.status in V2.

ALTER TABLE transactions
    ADD CONSTRAINT chk_transactions_direction
    CHECK (direction IN ('DEBIT', 'CREDIT'));
