ALTER TABLE bunching.ingestion_feed_cursor
    ADD COLUMN feed_scope VARCHAR(100) NOT NULL DEFAULT 'LIVE',
    ADD COLUMN demo_run_id UUID;
ALTER TABLE bunching.ingestion_feed_cursor
    ADD CONSTRAINT ck_ingestion_feed_cursor_scope CHECK (
        (feed_scope = 'LIVE' AND demo_run_id IS NULL) OR
        (feed_scope = 'DEMO:' || demo_run_id::text AND demo_run_id IS NOT NULL));

ALTER TABLE bunching.source_retry
    ADD COLUMN feed_scope VARCHAR(100) NOT NULL DEFAULT 'LIVE',
    ADD COLUMN demo_run_id UUID;
ALTER TABLE bunching.source_retry
    ADD CONSTRAINT ck_source_retry_scope CHECK (
        (feed_scope = 'LIVE' AND demo_run_id IS NULL) OR
        (feed_scope = 'DEMO:' || demo_run_id::text AND demo_run_id IS NOT NULL));
CREATE INDEX ix_source_retry_scope_deadline ON bunching.source_retry (feed_scope, next_attempt_at);

ALTER TABLE bunching.source_rejection
    ADD COLUMN classification VARCHAR(30) NOT NULL DEFAULT 'OBSERVATION_ONLY',
    ADD COLUMN feed_scope VARCHAR(100) NOT NULL DEFAULT 'LIVE',
    ADD COLUMN demo_run_id UUID;
ALTER TABLE bunching.source_rejection
    ADD CONSTRAINT ck_source_rejection_scope CHECK (
        (classification = 'OBSERVATION_ONLY' AND feed_scope = 'LIVE' AND demo_run_id IS NULL) OR
        (classification = 'SIMULATION' AND demo_run_id IS NOT NULL AND feed_scope = 'DEMO:' || demo_run_id::text));

ALTER TABLE bunching.source_processing_audit
    ADD COLUMN feed_scope VARCHAR(100) NOT NULL DEFAULT 'LIVE',
    ADD COLUMN demo_run_id UUID;
ALTER TABLE bunching.source_processing_audit DROP CONSTRAINT ck_source_processing_audit_classification;
ALTER TABLE bunching.source_processing_audit ADD CONSTRAINT ck_source_processing_audit_classification
    CHECK (classification IN ('OBSERVATION_ONLY', 'SIMULATION'));
ALTER TABLE bunching.source_processing_audit
    ADD CONSTRAINT ck_source_processing_audit_scope CHECK (
        (classification = 'OBSERVATION_ONLY' AND feed_scope = 'LIVE' AND demo_run_id IS NULL) OR
        (classification = 'SIMULATION' AND demo_run_id IS NOT NULL AND feed_scope = 'DEMO:' || demo_run_id::text));
CREATE INDEX ix_source_processing_audit_scope_time ON bunching.source_processing_audit (feed_scope, occurred_at);

ALTER TABLE bunching.bunching_observation
    ADD COLUMN feed_scope VARCHAR(100) NOT NULL DEFAULT 'LIVE',
    ADD COLUMN demo_run_id UUID;
ALTER TABLE bunching.bunching_observation DROP CONSTRAINT ck_bunching_observation_classification;
ALTER TABLE bunching.bunching_observation ADD CONSTRAINT ck_bunching_observation_classification
    CHECK (classification IN ('OBSERVATION_ONLY', 'SIMULATION'));
ALTER TABLE bunching.bunching_observation
    ADD CONSTRAINT ck_bunching_observation_scope CHECK (
        (classification = 'OBSERVATION_ONLY' AND feed_scope = 'LIVE' AND demo_run_id IS NULL) OR
        (classification = 'SIMULATION' AND demo_run_id IS NOT NULL AND feed_scope = 'DEMO:' || demo_run_id::text));
CREATE INDEX ix_bunching_observation_demo_run ON bunching.bunching_observation (demo_run_id, evaluated_at)
    WHERE classification = 'SIMULATION';

ALTER TABLE bunching.alert_event
    ADD COLUMN leading_observation_id UUID,
    ADD COLUMN following_observation_id UUID;
ALTER TABLE bunching.demo_alert_state
    ADD COLUMN leading_observation_id UUID,
    ADD COLUMN following_observation_id UUID;
