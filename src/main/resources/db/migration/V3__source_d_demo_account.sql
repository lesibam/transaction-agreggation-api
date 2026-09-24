-- V3: demo account for the optional SOURCE_D (S3/MinIO) demo source.
--
-- TransactionPersister resolves the owning account by source provider; without
-- this row, records ingested from SOURCE_D (enabled via the `s3demo` profile)
-- quarantine as NO_ACCOUNT. With it, the S3 demo runs end-to-end. The row is
-- inert in any deployment that does not register a SOURCE_D source.

INSERT INTO accounts (id, customer_id, account_type, currency, source_provider, created_by, updated_by)
VALUES (
    '00000000-0000-0000-0000-000000000104',
    '00000000-0000-0000-0000-000000000001',
    'CHECKING',
    'ZAR',
    'SOURCE_D',
    'system',
    'system'
)
ON CONFLICT DO NOTHING;
