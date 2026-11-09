ALTER TABLE disruption.disruption_event
    ADD COLUMN affected_observation_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE disruption.disruption_processing_state
    ADD COLUMN pending_affected_observation_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
