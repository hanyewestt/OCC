ALTER TABLE planning.advisory_proposal
    ADD COLUMN source_revision_key VARCHAR(100),
    ADD COLUMN candidate_type VARCHAR(30);

UPDATE planning.advisory_proposal
SET source_revision_key = CASE
        WHEN context->>'sourceRevision' IS NULL THEN 'observation:' || (context->>'sourceObservationId')
        ELSE 'revision:' || (context->>'sourceRevision')
    END,
    candidate_type = candidate->>'type';

ALTER TABLE planning.advisory_proposal
    ALTER COLUMN source_revision_key SET NOT NULL,
    ALTER COLUMN candidate_type SET NOT NULL;

CREATE UNIQUE INDEX uq_advisory_proposal_evidence
    ON planning.advisory_proposal (disruption_id, demo_run_id, bus_id, source_revision_key, candidate_type);
