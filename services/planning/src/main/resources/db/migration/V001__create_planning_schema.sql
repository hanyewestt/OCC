CREATE TABLE planning.legacy_import_ledger (
    source_schema VARCHAR(63) NOT NULL,
    source_table VARCHAR(63) NOT NULL,
    source_id UUID NOT NULL,
    target_id UUID NOT NULL,
    source_row_hash CHAR(64) NOT NULL,
    import_version INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_legacy_import_ledger PRIMARY KEY (source_schema, source_table, source_id),
    CONSTRAINT ck_legacy_import_ledger_hash CHECK (source_row_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_legacy_import_ledger_version CHECK (import_version > 0),
    CONSTRAINT ck_legacy_import_ledger_status CHECK (status IN ('APPLIED', 'QUARANTINED'))
);
CREATE INDEX ix_legacy_import_ledger_status
    ON planning.legacy_import_ledger (status, imported_at);

CREATE TABLE planning.planning_request (
    id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    producer VARCHAR(80) NOT NULL,
    event_id UUID NOT NULL,
    schema_version VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    failure_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    input_snapshot TEXT,
    CONSTRAINT pk_planning_request PRIMARY KEY (id),
    CONSTRAINT uq_planning_request_event UNIQUE (event_type, producer, event_id, schema_version),
    CONSTRAINT ck_planning_request_status CHECK (status IN
        ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED_RETRYABLE', 'FAILED_TERMINAL')),
    CONSTRAINT ck_planning_request_attempt_count CHECK (attempt_count >= 0)
);
CREATE INDEX ix_planning_request_claim
    ON planning.planning_request (status, next_attempt_at, lease_until, created_at);

CREATE TABLE planning.diagnosis_result (
    id UUID NOT NULL,
    request_id UUID NOT NULL,
    cause_code VARCHAR(40) NOT NULL,
    cause_subtype VARCHAR(100),
    likely_cause VARCHAR(1000) NOT NULL,
    confidence NUMERIC(4,3) NOT NULL,
    evidence_support_score NUMERIC(4,3) NOT NULL,
    supporting_evidence_ids JSONB NOT NULL,
    limitations JSONB NOT NULL,
    confidence_components JSONB NOT NULL,
    evidence_snapshot JSONB NOT NULL,
    evidence_snapshot_hash VARCHAR(64) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    model VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    schema_version VARCHAR(80) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_diagnosis_result PRIMARY KEY (id),
    CONSTRAINT uq_diagnosis_result_request UNIQUE (request_id),
    CONSTRAINT fk_diagnosis_result_request FOREIGN KEY (request_id)
        REFERENCES planning.planning_request (id),
    CONSTRAINT ck_diagnosis_result_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_diagnosis_result_score CHECK (evidence_support_score BETWEEN 0 AND 1),
    CONSTRAINT ck_diagnosis_result_evidence CHECK (jsonb_typeof(supporting_evidence_ids) = 'array'),
    CONSTRAINT ck_diagnosis_result_limitations CHECK (jsonb_typeof(limitations) = 'array'),
    CONSTRAINT ck_diagnosis_result_components CHECK (jsonb_typeof(confidence_components) = 'object'),
    CONSTRAINT ck_diagnosis_result_snapshot CHECK (jsonb_typeof(evidence_snapshot) = 'object')
);
CREATE INDEX ix_diagnosis_result_cause ON planning.diagnosis_result (cause_code, completed_at);

CREATE TABLE planning.recommendation_job (
    id UUID NOT NULL,
    diagnosis_id UUID NOT NULL,
    payload_json TEXT NOT NULL,
    payload_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    last_http_status INTEGER,
    failure_code VARCHAR(80),
    response_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    terminal_at TIMESTAMPTZ,
    CONSTRAINT pk_recommendation_job PRIMARY KEY (id),
    CONSTRAINT uq_recommendation_job_diagnosis UNIQUE (diagnosis_id),
    CONSTRAINT fk_recommendation_job_diagnosis FOREIGN KEY (diagnosis_id)
        REFERENCES planning.diagnosis_result (id),
    CONSTRAINT ck_recommendation_job_fingerprint CHECK (payload_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_recommendation_job_status CHECK (status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED_TERMINAL')),
    CONSTRAINT ck_recommendation_job_attempts CHECK (attempt_count >= 0)
);
CREATE INDEX ix_recommendation_job_pending
    ON planning.recommendation_job (status, next_attempt_at, lease_until, created_at);

CREATE TABLE planning.recommendation (
    recommendation_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    bunching_event_id UUID NOT NULL,
    direction_id SMALLINT NOT NULL,
    adjustment_seconds SMALLINT NOT NULL,
    request_payload JSONB NOT NULL,
    mitigation_context JSONB NOT NULL,
    response_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_recommendation PRIMARY KEY (recommendation_id),
    CONSTRAINT ck_recommendation_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_recommendation_direction_id CHECK (direction_id IN (1, 2)),
    CONSTRAINT ck_recommendation_adjustment_seconds CHECK (adjustment_seconds BETWEEN 1 AND 120),
    CONSTRAINT ck_recommendation_request_payload CHECK (jsonb_typeof(request_payload) = 'object'),
    CONSTRAINT ck_recommendation_mitigation_context CHECK (jsonb_typeof(mitigation_context) = 'object'),
    CONSTRAINT ck_recommendation_response_payload CHECK (jsonb_typeof(response_payload) = 'object'),
    CONSTRAINT ck_recommendation_bunching_event_projection CHECK (
        (jsonb_typeof(request_payload #> '{bunchingEvent,bunchingEventId}') = 'string'
         AND request_payload #>> '{bunchingEvent,bunchingEventId}' = bunching_event_id::text) IS TRUE),
    CONSTRAINT ck_recommendation_direction_projection CHECK (
        (jsonb_typeof(request_payload #> '{bunchingEvent,directionId}') = 'number'
         AND request_payload #>> '{bunchingEvent,directionId}' = direction_id::text) IS TRUE),
    CONSTRAINT ck_recommendation_adjustment_projection CHECK (
        (jsonb_typeof(response_payload -> 'adjustmentSeconds') = 'number'
         AND response_payload ->> 'adjustmentSeconds' = adjustment_seconds::text) IS TRUE)
);
CREATE INDEX ix_recommendation_event_created_at
    ON planning.recommendation (bunching_event_id, created_at DESC);
CREATE INDEX ix_recommendation_created_at ON planning.recommendation (created_at);

CREATE TABLE planning.model_invocation_record (
    id UUID NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    request_id UUID,
    recommendation_id UUID,
    correlation_id UUID,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(128) NOT NULL,
    provider_response_id VARCHAR(255),
    prompt_version VARCHAR(80) NOT NULL,
    output_schema_version VARCHAR(80) NOT NULL,
    evidence_snapshot_hash VARCHAR(64),
    attempt_type VARCHAR(30),
    attempt_number INTEGER,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    latency_millis BIGINT NOT NULL,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    outcome VARCHAR(32) NOT NULL,
    failure_category VARCHAR(80),
    candidate_payload JSONB,
    rejection_reason VARCHAR(64),
    deterministic_fallback_used BOOLEAN,
    input_snapshot TEXT,
    generated_response TEXT,
    CONSTRAINT pk_model_invocation_record PRIMARY KEY (id),
    CONSTRAINT fk_model_invocation_record_request FOREIGN KEY (request_id)
        REFERENCES planning.planning_request (id),
    CONSTRAINT fk_model_invocation_record_recommendation FOREIGN KEY (recommendation_id)
        REFERENCES planning.recommendation (recommendation_id),
    CONSTRAINT ck_model_invocation_purpose CHECK (purpose IN ('DIAGNOSIS', 'RECOMMENDATION')),
    CONSTRAINT ck_model_invocation_anchor CHECK (
        (purpose = 'DIAGNOSIS' AND request_id IS NOT NULL AND recommendation_id IS NULL
            AND correlation_id IS NULL AND attempt_type IN ('INITIAL', 'SCHEMA_REPAIR')
            AND attempt_number > 0 AND deterministic_fallback_used IS NULL)
        OR (purpose = 'RECOMMENDATION' AND request_id IS NULL AND correlation_id IS NOT NULL
            AND attempt_type IS NULL AND attempt_number IS NULL AND deterministic_fallback_used IS NOT NULL)),
    CONSTRAINT ck_model_invocation_outcome CHECK (
        (purpose = 'DIAGNOSIS' AND outcome IN ('VALID', 'INVALID', 'PROVIDER_FAILURE'))
        OR (purpose = 'RECOMMENDATION' AND outcome IN
            ('ACCEPTED', 'REJECTED', 'MISMATCHED', 'EMPTY_RESPONSE', 'PROVIDER_FAILURE'))),
    CONSTRAINT ck_model_invocation_attempt CHECK (attempt_number IS NULL OR attempt_number > 0),
    CONSTRAINT ck_model_invocation_latency CHECK (latency_millis >= 0),
    CONSTRAINT ck_model_invocation_timestamps CHECK (completed_at >= started_at),
    CONSTRAINT ck_model_invocation_prompt_tokens CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0),
    CONSTRAINT ck_model_invocation_completion_tokens CHECK (completion_tokens IS NULL OR completion_tokens >= 0),
    CONSTRAINT ck_model_invocation_total_tokens CHECK (total_tokens IS NULL OR total_tokens >= 0),
    CONSTRAINT ck_model_invocation_candidate_payload CHECK (
        candidate_payload IS NULL OR jsonb_typeof(candidate_payload) = 'object'),
    CONSTRAINT ck_model_invocation_recommendation_state CHECK (
        purpose = 'DIAGNOSIS'
        OR (outcome = 'ACCEPTED' AND candidate_payload IS NOT NULL AND rejection_reason IS NULL
            AND failure_category IS NULL AND deterministic_fallback_used = FALSE)
        OR (outcome = 'REJECTED' AND candidate_payload IS NOT NULL AND rejection_reason IS NOT NULL
            AND failure_category IS NULL AND deterministic_fallback_used = TRUE)
        OR (outcome = 'MISMATCHED' AND candidate_payload IS NOT NULL AND rejection_reason IS NULL
            AND failure_category IS NULL AND deterministic_fallback_used = TRUE)
        OR (outcome = 'EMPTY_RESPONSE' AND candidate_payload IS NULL AND rejection_reason IS NULL
            AND failure_category IS NULL AND deterministic_fallback_used = TRUE)
        OR (outcome = 'PROVIDER_FAILURE' AND candidate_payload IS NULL AND rejection_reason IS NULL
            AND failure_category IS NOT NULL AND deterministic_fallback_used = TRUE))
);
CREATE INDEX ix_model_invocation_request_started
    ON planning.model_invocation_record (request_id, started_at);
CREATE INDEX ix_model_invocation_recommendation
    ON planning.model_invocation_record (recommendation_id);
CREATE INDEX ix_model_invocation_correlation
    ON planning.model_invocation_record (correlation_id);
CREATE INDEX ix_model_invocation_purpose_completed
    ON planning.model_invocation_record (purpose, completed_at DESC);
CREATE INDEX ix_model_invocation_outcome_completed
    ON planning.model_invocation_record (outcome, completed_at DESC);

COMMENT ON TABLE planning.legacy_import_ledger IS
    'Idempotent row-level evidence for source-schema imports into Planning.';
COMMENT ON COLUMN planning.model_invocation_record.input_snapshot IS
    'Immutable model input retained separately from operational source rows; NULL means the source did not retain it.';
COMMENT ON COLUMN planning.model_invocation_record.generated_response IS
    'Actual model text when retained by the source; NULL means no response was available.';
