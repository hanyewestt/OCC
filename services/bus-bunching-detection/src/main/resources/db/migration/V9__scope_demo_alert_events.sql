ALTER TABLE bunching.alert_event DROP CONSTRAINT ck_alert_event_classification;
ALTER TABLE bunching.alert_event ADD COLUMN demo_run_id UUID;
ALTER TABLE bunching.alert_event ADD COLUMN feed_scope VARCHAR(100) NOT NULL DEFAULT 'LIVE';
ALTER TABLE bunching.alert_event ADD CONSTRAINT ck_alert_event_classification
    CHECK (classification IN ('OBSERVATION_ONLY', 'SIMULATION'));
ALTER TABLE bunching.alert_event ADD CONSTRAINT ck_alert_event_demo_scope CHECK (
    (classification = 'OBSERVATION_ONLY' AND demo_run_id IS NULL AND feed_scope = 'LIVE') OR
    (classification = 'SIMULATION' AND demo_run_id IS NOT NULL AND feed_scope LIKE 'DEMO:%'));
CREATE INDEX ix_alert_event_demo_run ON bunching.alert_event (demo_run_id, triggered_at)
    WHERE classification = 'SIMULATION';
