-- Retain a revision's snapshot even if its older predecessor expires under retention.
ALTER TABLE planning.recommendation
    ADD COLUMN previous_recommendation_id UUID REFERENCES planning.recommendation(recommendation_id) ON DELETE SET NULL,
    ADD COLUMN revision_raw_observation_id UUID,
    ADD CONSTRAINT uq_recommendation_previous UNIQUE (previous_recommendation_id),
    ADD CONSTRAINT ck_recommendation_revision_snapshot CHECK (
        previous_recommendation_id IS NULL OR revision_raw_observation_id IS NOT NULL),
    ADD CONSTRAINT ck_recommendation_revision_distinct CHECK (
        previous_recommendation_id IS DISTINCT FROM recommendation_id);
