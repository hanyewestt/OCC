CREATE TABLE planning.advisory_proposal (
    id UUID NOT NULL, event_id UUID NOT NULL, disruption_id UUID NOT NULL, demo_run_id UUID NOT NULL,
    bus_id VARCHAR(80) NOT NULL, status VARCHAR(20) NOT NULL, preview TEXT NOT NULL,
    context JSONB NOT NULL, bus JSONB NOT NULL, candidate JSONB NOT NULL, final_candidate JSONB,
    model VARCHAR(150) NOT NULL, model_input TEXT NOT NULL, model_output TEXT NOT NULL,
    controller_subject VARCHAR(200), controller_rationale VARCHAR(2000), replaced_instruction_id UUID,
    created_at TIMESTAMPTZ NOT NULL, decided_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_advisory_proposal PRIMARY KEY (id),
    CONSTRAINT uq_advisory_proposal_target UNIQUE (event_id,demo_run_id,bus_id),
    CONSTRAINT ck_advisory_proposal_status CHECK (status IN ('PENDING','APPROVED','EXPIRED')),
    CONSTRAINT ck_advisory_proposal_decision CHECK ((status <> 'APPROVED')
        OR (final_candidate IS NOT NULL AND controller_subject IS NOT NULL AND decided_at IS NOT NULL))
);
COMMENT ON COLUMN planning.advisory_proposal.final_candidate IS 'Null until a controller approves final structured parameters.';
CREATE INDEX ix_advisory_proposal_run ON planning.advisory_proposal(demo_run_id,created_at);
