-- Durable cooldown and queue position for incomplete or temporarily failed observations.
-- Completed/rejected sources are excluded by the consumer even if retry metadata remains.
CREATE TABLE bunching.source_retry (
    raw_observation_id UUID NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_source_retry PRIMARY KEY (raw_observation_id)
);
CREATE INDEX ix_source_retry_next_attempt_at ON bunching.source_retry (next_attempt_at);
