CREATE TABLE recommendation_record (
    recommendation_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    request_id UUID NOT NULL,
    bunching_event_id UUID NOT NULL,
    diagnosis_id UUID NOT NULL,
    classification VARCHAR(32) NOT NULL,
    service_id VARCHAR(20) NOT NULL,
    direction_id SMALLINT NOT NULL,
    monitored_stop_code VARCHAR(12) NOT NULL,
    mitigation_type VARCHAR(32) NOT NULL,
    recommendation_status VARCHAR(32) NOT NULL,
    rationale TEXT NOT NULL,
    target_bus_reference VARCHAR(64) NOT NULL,
    control_stop_code VARCHAR(12) NOT NULL,
    adjustment_seconds SMALLINT NOT NULL,
    request_payload JSONB NOT NULL,
    mitigation_context JSONB NOT NULL,
    response_payload JSONB NOT NULL,
    evidence_ids JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_recommendation_record PRIMARY KEY (recommendation_id),
    CONSTRAINT uq_recommendation_record_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_recommendation_record_identity CHECK (recommendation_id = idempotency_key),
    CONSTRAINT ck_recommendation_record_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_recommendation_record_direction_id CHECK (direction_id IN (1, 2)),
    CONSTRAINT ck_recommendation_record_adjustment_seconds CHECK (adjustment_seconds BETWEEN 1 AND 120),
    CONSTRAINT ck_recommendation_record_request_payload CHECK (jsonb_typeof(request_payload) = 'object'),
    CONSTRAINT ck_recommendation_record_mitigation_context CHECK (jsonb_typeof(mitigation_context) = 'object'),
    CONSTRAINT ck_recommendation_record_response_payload CHECK (jsonb_typeof(response_payload) = 'object'),
    CONSTRAINT ck_recommendation_record_evidence_ids CHECK (jsonb_typeof(evidence_ids) = 'array')
);

CREATE INDEX ix_recommendation_record_bunching_event_id
    ON recommendation_record (bunching_event_id);
CREATE INDEX ix_recommendation_record_diagnosis_id
    ON recommendation_record (diagnosis_id);
CREATE INDEX ix_recommendation_record_created_at
    ON recommendation_record (created_at);

COMMENT ON COLUMN recommendation_record.request_hash IS
    'SHA-256 of the validated canonical API request; detects idempotency-key reuse with different content.';
COMMENT ON COLUMN recommendation_record.request_payload IS
    'Immutable validated event and diagnosis input retained for audit and replay.';
COMMENT ON COLUMN recommendation_record.mitigation_context IS
    'Fixture or Operations-supplied limits and validation evidence used by deterministic policy.';
COMMENT ON COLUMN recommendation_record.response_payload IS
    'Immutable validated recommendation returned by the engine.';
