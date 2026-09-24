-- Raw ingestion is usable before topology import. Missing topology is retained as unknown.
ALTER TABLE bunching.bunching_observation ALTER COLUMN reference_version_id DROP NOT NULL;

CREATE TABLE bunching.source_rejection (
    raw_observation_id UUID NOT NULL,
    rejected_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(80) NOT NULL,
    CONSTRAINT pk_source_rejection PRIMARY KEY (raw_observation_id)
);
