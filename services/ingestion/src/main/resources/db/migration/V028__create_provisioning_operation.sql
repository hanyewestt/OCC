CREATE TABLE ingestion.provisioning_operation (
 id UUID CONSTRAINT pk_provisioning_operation PRIMARY KEY,
 actor_subject VARCHAR(255) NOT NULL, idempotency_key VARCHAR(200) NOT NULL,
 email VARCHAR(254) NOT NULL, display_name VARCHAR(200) NOT NULL, role VARCHAR(20) NOT NULL,
 status VARCHAR(30) NOT NULL, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 reconciliation_after TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 account_id UUID, result_name TEXT, result_email TEXT, account_created_at TIMESTAMPTZ,
 account_last_sign_in_at TIMESTAMPTZ, result_synchronized_at TIMESTAMPTZ, failure_code VARCHAR(40),
 CONSTRAINT uq_provisioning_operation_actor_key UNIQUE(actor_subject,idempotency_key),
 CONSTRAINT ck_provisioning_operation_role CHECK(role IN ('CONTROLLER','CAPTAIN')),
 CONSTRAINT ck_provisioning_operation_status CHECK(status IN ('PENDING','PASSWORD_REQUIRED','SUCCEEDED','REJECTED')),
 CONSTRAINT ck_provisioning_operation_result CHECK((status='SUCCEEDED')=(account_id IS NOT NULL)),
 CONSTRAINT ck_provisioning_operation_failure CHECK(failure_code IS NULL OR failure_code IN ('DUPLICATE_EMAIL','PASSWORD_POLICY'))
);
-- Passwords and password-derived values are deliberately absent. Pending alone reserves an email.
-- Late old completion remains recordable while a fresh operation reserves the same email.
CREATE UNIQUE INDEX uq_provisioning_operation_pending_email ON ingestion.provisioning_operation(email) WHERE status='PENDING';
CREATE INDEX ix_provisioning_operation_recovery ON ingestion.provisioning_operation(status,reconciliation_after);
COMMENT ON COLUMN ingestion.provisioning_operation.account_id IS 'Nullable until exact provider operation attribution confirms success; success fields preserve original replay response.';
COMMENT ON COLUMN ingestion.provisioning_operation.reconciliation_after IS 'Crash dispatch grace only; never permission to retry create or proof of future provider absence.';
