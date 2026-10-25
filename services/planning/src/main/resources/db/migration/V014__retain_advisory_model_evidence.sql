CREATE TABLE planning.advisory_model_evidence (
    proposal_id UUID NOT NULL PRIMARY KEY,
    event_id UUID NOT NULL,
    disruption_id UUID NOT NULL,
    demo_run_id UUID NOT NULL,
    bus_id VARCHAR(80) NOT NULL,
    source_revision_key VARCHAR(100) NOT NULL,
    candidate_type VARCHAR(30) NOT NULL,
    model VARCHAR(150) NOT NULL,
    model_input TEXT NOT NULL,
    model_output TEXT NOT NULL,
    candidate JSONB NOT NULL,
    final_candidate JSONB,
    controller_subject VARCHAR(200),
    controller_rationale TEXT,
    replaced_instruction_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    CONSTRAINT ck_advisory_model_evidence_candidate CHECK (jsonb_typeof(candidate)='object'),
    CONSTRAINT ck_advisory_model_evidence_final CHECK (final_candidate IS NULL OR jsonb_typeof(final_candidate)='object')
);

INSERT INTO planning.advisory_model_evidence (
    proposal_id, event_id, disruption_id, demo_run_id, bus_id, source_revision_key, candidate_type,
    model, model_input, model_output, candidate, final_candidate, controller_subject, controller_rationale,
    replaced_instruction_id, created_at, decided_at)
SELECT id, event_id, disruption_id, demo_run_id, bus_id, source_revision_key, candidate_type,
       model, model_input, model_output, candidate, final_candidate, controller_subject, controller_rationale,
       replaced_instruction_id, created_at, decided_at
FROM planning.advisory_proposal;

CREATE INDEX ix_advisory_model_evidence_retention ON planning.advisory_model_evidence(created_at);
COMMENT ON TABLE planning.advisory_model_evidence IS
    'Long-lived AI input/output and controller decision evidence; independent of short-lived operational proposals.';
