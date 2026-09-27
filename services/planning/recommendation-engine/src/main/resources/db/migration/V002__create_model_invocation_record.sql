CREATE TABLE model_invocation_record (
    invocation_id UUID NOT NULL,
    recommendation_id UUID NOT NULL,
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
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_model_invocation_record PRIMARY KEY (invocation_id),
    CONSTRAINT fk_model_invocation_recommendation FOREIGN KEY (recommendation_id)
        REFERENCES recommendation_record (recommendation_id),
    CONSTRAINT ck_model_invocation_provider CHECK (provider IN ('GEMINI')),
    CONSTRAINT ck_model_invocation_outcome CHECK (
        outcome IN ('ACCEPTED', 'REJECTED', 'MISMATCHED', 'EMPTY_RESPONSE', 'PROVIDER_FAILURE')
    ),
    CONSTRAINT ck_model_invocation_candidate_payload CHECK (
        candidate_payload IS NULL OR jsonb_typeof(candidate_payload) = 'object'
    ),
    CONSTRAINT ck_model_invocation_latency CHECK (latency_milliseconds >= 0),
    CONSTRAINT ck_model_invocation_timestamps CHECK (completed_at >= started_at),
    CONSTRAINT ck_model_invocation_prompt_tokens CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0),
    CONSTRAINT ck_model_invocation_completion_tokens CHECK (completion_tokens IS NULL OR completion_tokens >= 0),
    CONSTRAINT ck_model_invocation_total_tokens CHECK (total_tokens IS NULL OR total_tokens >= 0),
    CONSTRAINT ck_model_invocation_outcome_state CHECK (
        (outcome = 'ACCEPTED'
            AND candidate_payload IS NOT NULL
            AND rejection_reason IS NULL
            AND failure_category IS NULL
            AND deterministic_fallback_used = FALSE)
        OR (outcome = 'REJECTED'
            AND candidate_payload IS NOT NULL
            AND rejection_reason IS NOT NULL
            AND failure_category IS NULL
            AND deterministic_fallback_used = TRUE)
        OR (outcome = 'MISMATCHED'
            AND candidate_payload IS NOT NULL
            AND rejection_reason IS NULL
            AND failure_category IS NULL
            AND deterministic_fallback_used = TRUE)
        OR (outcome = 'EMPTY_RESPONSE'
            AND candidate_payload IS NULL
            AND rejection_reason IS NULL
            AND failure_category IS NULL
            AND deterministic_fallback_used = TRUE)
        OR (outcome = 'PROVIDER_FAILURE'
            AND candidate_payload IS NULL
            AND rejection_reason IS NULL
            AND failure_category IS NOT NULL
            AND deterministic_fallback_used = TRUE)
    )
);

CREATE INDEX ix_model_invocation_recommendation_id
    ON model_invocation_record (recommendation_id);
CREATE INDEX ix_model_invocation_provider_model
    ON model_invocation_record (provider, model);
CREATE INDEX ix_model_invocation_outcome
    ON model_invocation_record (outcome);
CREATE INDEX ix_model_invocation_created_at
    ON model_invocation_record (created_at);

COMMENT ON TABLE model_invocation_record IS
    'Sanitized model-call audit data associated with a stored mitigation recommendation.';
COMMENT ON COLUMN model_invocation_record.candidate_payload IS
    'Structured candidate only; raw provider responses and rendered prompts are intentionally not retained.';
COMMENT ON COLUMN model_invocation_record.failure_category IS
    'Stable sanitized category only; provider error bodies and stack traces are not retained.';
