CREATE ROLE occ_planning LOGIN PASSWORD 'synthetic-planning';
CREATE SCHEMA planning AUTHORIZATION occ_planning;
CREATE SCHEMA diagnosis;
CREATE SCHEMA recommendation;

CREATE TABLE diagnosis.diagnosis_request (
    id UUID PRIMARY KEY,
    event_type VARCHAR(40) NOT NULL,
    producer VARCHAR(80) NOT NULL,
    event_id UUID NOT NULL,
    schema_version VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    failure_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    input_snapshot TEXT
);

CREATE TABLE diagnosis.root_cause_diagnosis (
    id UUID PRIMARY KEY,
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
    completed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE diagnosis.recommendation_delivery (
    id UUID PRIMARY KEY,
    diagnosis_id UUID NOT NULL,
    payload_json TEXT NOT NULL,
    payload_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    last_http_status INTEGER,
    failure_code VARCHAR(80),
    response_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    terminal_at TIMESTAMPTZ
);

CREATE TABLE diagnosis.model_invocation_record (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    provider VARCHAR(30) NOT NULL,
    configured_model VARCHAR(100) NOT NULL,
    response_id VARCHAR(200),
    prompt_version VARCHAR(80) NOT NULL,
    schema_version VARCHAR(80) NOT NULL,
    evidence_snapshot_hash VARCHAR(64),
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
    input_snapshot TEXT,
    generated_response TEXT
);

CREATE TABLE recommendation.recommendation_record (
    recommendation_id UUID PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    bunching_event_id UUID NOT NULL,
    direction_id SMALLINT NOT NULL,
    adjustment_seconds SMALLINT NOT NULL,
    request_payload JSONB NOT NULL,
    mitigation_context JSONB NOT NULL,
    response_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE recommendation.model_invocation_record (
    invocation_id UUID PRIMARY KEY,
    recommendation_id UUID,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    output_schema_version VARCHAR(64) NOT NULL,
    provider_response_id VARCHAR(255),
    outcome VARCHAR(32) NOT NULL,
    candidate_payload JSONB,
    rejection_reason VARCHAR(64),
    failure_category VARCHAR(64),
    deterministic_fallback_used BOOLEAN NOT NULL,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    latency_milliseconds BIGINT NOT NULL,
    correlation_id UUID NOT NULL,
    input_snapshot TEXT,
    generated_response TEXT
);

INSERT INTO diagnosis.diagnosis_request VALUES
('00000000-0000-0000-0000-000000000001', 'BUS_BUNCHING', 'bus-bunching-detection',
 '00000000-0000-0000-0000-000000000002', 'bunching-event-v1', 'PENDING', 2,
 '2026-10-15T00:00:00Z', '2026-10-15T00:05:00Z', 'RETRYABLE',
 '2026-10-14T23:00:00Z', '2026-10-14T23:30:00Z', 7, '{"captured":true}');

INSERT INTO diagnosis.root_cause_diagnosis VALUES
('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
 'BREAKDOWN', 'VEHICLE', 'Synthetic retained diagnosis', 0.850, 0.800,
 '["arrival-1"]', '["synthetic limitation"]', '{"evidence":0.8}', '{"snapshot":true}',
 repeat('a', 64), 'GOOGLE_GENAI', repeat('d', 100), 'prompt-v1', 'schema-v1', '2026-10-14T23:30:00Z');

INSERT INTO diagnosis.recommendation_delivery VALUES
('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000003',
 '{"event":"retained"}', repeat('b', 64), 'PENDING', 1, '2026-10-15T00:00:00Z', NULL,
 503, 'PEER_RETRY', NULL, '2026-10-14T23:30:00Z', NULL, NULL);

INSERT INTO diagnosis.model_invocation_record VALUES
('f0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
 'GOOGLE_GENAI', repeat('d', 100), repeat('r', 200), 'prompt-v1', 'schema-v1', repeat('c', 64),
 'INITIAL', 1, '2026-10-14T23:29:00Z', '2026-10-14T23:29:01Z', 1000, 10, 20, 30,
 'VALID', NULL, '{"input":"kept"}', '{"output":"kept"}');

INSERT INTO recommendation.recommendation_record VALUES
('00000000-0000-0000-0000-000000000005', repeat('e', 64),
 '00000000-0000-0000-0000-000000000006', 1, 120,
 '{"bunchingEvent":{"bunchingEventId":"00000000-0000-0000-0000-000000000006","directionId":1}}',
 '{"maximumHoldSeconds":120}', '{"adjustmentSeconds":120}', '2026-10-14T23:35:00Z');

INSERT INTO recommendation.model_invocation_record VALUES
('f0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000005',
 'GEMINI', repeat('m', 128), 'prompt-v1', 'schema-v1', repeat('p', 255), 'ACCEPTED',
 '{"candidate":true}', NULL, NULL, FALSE, 11, 22, 33,
 '2026-10-14T23:34:00Z', '2026-10-14T23:34:01Z', 1000,
 '00000000-0000-0000-0000-000000000005', '{"input":"candidate"}', '{"output":"candidate"}');

GRANT USAGE ON SCHEMA diagnosis, recommendation TO occ_planning;
GRANT SELECT ON ALL TABLES IN SCHEMA diagnosis, recommendation TO occ_planning;
