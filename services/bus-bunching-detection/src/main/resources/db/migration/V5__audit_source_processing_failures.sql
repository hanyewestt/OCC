-- Append-only processing history, separate from mutable retry queue state and telemetry retention.
CREATE TABLE bunching.source_processing_audit (
    id UUID NOT NULL,
    raw_observation_id UUID NOT NULL,
    poll_run_id UUID, -- May be unavailable if source telemetry was already removed.
    source_key VARCHAR(200), -- May be unavailable after source deletion.
    fetched_at TIMESTAMPTZ, -- May be unavailable after source deletion.
    actor VARCHAR(80) NOT NULL,
    action VARCHAR(40) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(80) NOT NULL,
    next_attempt_at TIMESTAMPTZ, -- Present only for a deferred attempt.
    classification VARCHAR(30) NOT NULL,
    CONSTRAINT pk_source_processing_audit PRIMARY KEY (id),
    CONSTRAINT ck_source_processing_audit_action CHECK (action IN ('SOURCE_REJECTED', 'SOURCE_DEFERRED')),
    CONSTRAINT ck_source_processing_audit_retry CHECK (
        (action = 'SOURCE_DEFERRED' AND next_attempt_at IS NOT NULL) OR
        (action = 'SOURCE_REJECTED' AND next_attempt_at IS NULL)),
    CONSTRAINT ck_source_processing_audit_classification CHECK (classification = 'OBSERVATION_ONLY')
);
CREATE INDEX ix_source_processing_audit_source_time ON bunching.source_processing_audit (raw_observation_id, occurred_at);
CREATE INDEX ix_source_processing_audit_poll_run_id ON bunching.source_processing_audit (poll_run_id);
