ALTER TABLE diagnosis.model_invocation_record
    ADD COLUMN input_snapshot TEXT,
    ADD COLUMN generated_response TEXT,
    ALTER COLUMN evidence_snapshot_hash DROP NOT NULL;
COMMENT ON COLUMN diagnosis.model_invocation_record.generated_response IS
    'Actual model text, including invalid output; NULL when no response exists or for legacy attempts.';
COMMENT ON COLUMN diagnosis.model_invocation_record.input_snapshot IS
    'Immutable model evidence and repair feedback, retained independently of operational source rows.';
