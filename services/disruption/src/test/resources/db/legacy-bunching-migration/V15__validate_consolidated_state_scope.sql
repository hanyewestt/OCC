-- Dropping feed_scope in V13 also dropped the old multi-column scope check.
ALTER TABLE bunching.alert_event ADD CONSTRAINT ck_alert_event_run_classification CHECK (
    (classification = 'OBSERVATION_ONLY' AND demo_run_id IS NULL)
    OR (classification = 'SIMULATION' AND demo_run_id IS NOT NULL));
ALTER TABLE bunching.source_processing_state ADD CONSTRAINT ck_source_processing_state_result CHECK (
    threshold_seconds IS NULL OR threshold_seconds > 0);
ALTER TABLE bunching.source_processing_state ADD CONSTRAINT ck_source_processing_state_count CHECK (
    bunched_group_count IS NULL OR bunched_group_count >= 0);
