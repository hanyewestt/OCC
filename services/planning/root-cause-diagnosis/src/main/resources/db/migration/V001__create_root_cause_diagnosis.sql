CREATE TABLE diagnosis.diagnosis_request (
    id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    producer VARCHAR(80) NOT NULL,
    event_id UUID NOT NULL,
    schema_version VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    diagnosis_id UUID,
    failure_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_diagnosis_request PRIMARY KEY (id),
    CONSTRAINT uq_diagnosis_request_event UNIQUE (event_type, producer, event_id, schema_version),
    CONSTRAINT ck_diagnosis_request_status CHECK (status IN
        ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED_RETRYABLE', 'FAILED_TERMINAL')),
    CONSTRAINT ck_diagnosis_request_attempt_count CHECK (attempt_count >= 0)
);
CREATE INDEX ix_diagnosis_request_claim ON diagnosis.diagnosis_request (status, next_attempt_at, lease_until, created_at);

CREATE TABLE diagnosis.root_cause_diagnosis (
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
    CONSTRAINT pk_root_cause_diagnosis PRIMARY KEY (id),
    CONSTRAINT uq_root_cause_diagnosis_request UNIQUE (request_id),
    CONSTRAINT fk_root_cause_diagnosis_request FOREIGN KEY (request_id)
        REFERENCES diagnosis.diagnosis_request (id),
    CONSTRAINT ck_root_cause_diagnosis_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_root_cause_diagnosis_score CHECK (evidence_support_score BETWEEN 0 AND 1),
    CONSTRAINT ck_root_cause_diagnosis_evidence CHECK (jsonb_typeof(supporting_evidence_ids) = 'array'),
    CONSTRAINT ck_root_cause_diagnosis_limitations CHECK (jsonb_typeof(limitations) = 'array'),
    CONSTRAINT ck_root_cause_diagnosis_components CHECK (jsonb_typeof(confidence_components) = 'object'),
    CONSTRAINT ck_root_cause_diagnosis_snapshot CHECK (jsonb_typeof(evidence_snapshot) = 'object')
);
CREATE INDEX ix_root_cause_diagnosis_cause ON diagnosis.root_cause_diagnosis (cause_code, completed_at);

CREATE TABLE diagnosis.model_invocation_record (
    id UUID NOT NULL,
    request_id UUID NOT NULL,
    provider VARCHAR(30) NOT NULL,
    configured_model VARCHAR(100) NOT NULL,
    response_id VARCHAR(200),
    prompt_version VARCHAR(80) NOT NULL,
    schema_version VARCHAR(80) NOT NULL,
    evidence_snapshot_hash VARCHAR(64) NOT NULL,
    invocation_type VARCHAR(30) NOT NULL,
    attempt_number INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    latency_millis BIGINT NOT NULL,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    validation_outcome VARCHAR(30) NOT NULL,
    failure_category VARCHAR(80),
    CONSTRAINT pk_model_invocation_record PRIMARY KEY (id),
    CONSTRAINT fk_model_invocation_record_request FOREIGN KEY (request_id)
        REFERENCES diagnosis.diagnosis_request (id),
    CONSTRAINT ck_model_invocation_record_type CHECK (invocation_type IN ('INITIAL', 'SCHEMA_REPAIR')),
    CONSTRAINT ck_model_invocation_record_attempt CHECK (attempt_number > 0),
    CONSTRAINT ck_model_invocation_record_latency CHECK (latency_millis >= 0)
);
CREATE INDEX ix_model_invocation_record_request ON diagnosis.model_invocation_record (request_id, started_at);

CREATE TABLE diagnosis.recommendation_delivery (
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
    CONSTRAINT pk_recommendation_delivery PRIMARY KEY (id),
    CONSTRAINT uq_recommendation_delivery_diagnosis UNIQUE (diagnosis_id),
    CONSTRAINT fk_recommendation_delivery_diagnosis FOREIGN KEY (diagnosis_id)
        REFERENCES diagnosis.root_cause_diagnosis (id),
    CONSTRAINT ck_recommendation_delivery_status CHECK (status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED_TERMINAL')),
    CONSTRAINT ck_recommendation_delivery_attempts CHECK (attempt_count >= 0)
);
CREATE INDEX ix_recommendation_delivery_pending ON diagnosis.recommendation_delivery (status, next_attempt_at, lease_until, created_at);
