-- One immutable evaluation per successful source observation, across all service groups.
-- Source UUIDs have no cross-schema FKs: ingestion retention must not erase audit evidence.
CREATE TABLE bunching.bunching_observation (
    raw_observation_id UUID NOT NULL,
    poll_run_id UUID NOT NULL,
    reference_version_id UUID NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    threshold_seconds NUMERIC(30, 9) NOT NULL,
    stale_after_seconds NUMERIC(30, 9) NOT NULL,
    rule_version VARCHAR(80) NOT NULL,
    classification VARCHAR(30) NOT NULL,
    bunched_group_count INTEGER NOT NULL,
    evidence JSONB NOT NULL,
    CONSTRAINT pk_bunching_observation PRIMARY KEY (raw_observation_id),
    CONSTRAINT ck_bunching_observation_threshold CHECK (threshold_seconds > 0),
    CONSTRAINT ck_bunching_observation_stale_after CHECK (stale_after_seconds > 0),
    CONSTRAINT ck_bunching_observation_count CHECK (bunched_group_count >= 0),
    CONSTRAINT ck_bunching_observation_classification CHECK (classification = 'OBSERVATION_ONLY'),
    CONSTRAINT ck_bunching_observation_evidence CHECK (jsonb_typeof(evidence) = 'object')
);

CREATE INDEX ix_bunching_observation_evaluated_at ON bunching.bunching_observation (evaluated_at);
CREATE INDEX ix_bunching_observation_poll_run_id ON bunching.bunching_observation (poll_run_id);
