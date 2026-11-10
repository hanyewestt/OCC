-- Nullable fields preserve compatibility with processing state created before this release.
ALTER TABLE disruption.disruption_processing_state
    ADD COLUMN current_bunching_state TEXT,
    ADD COLUMN current_raw_observation_id UUID,
    ADD COLUMN current_evidence_valid_until TIMESTAMPTZ,
    ADD COLUMN current_affected_observation_ids JSONB;
