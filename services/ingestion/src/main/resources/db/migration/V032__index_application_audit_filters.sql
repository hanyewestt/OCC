DROP INDEX IF EXISTS ingestion.idx_application_audit_event_type_time;
DROP INDEX IF EXISTS ingestion.idx_application_audit_actor_time;
DROP INDEX IF EXISTS ingestion.idx_application_audit_run_time;
DROP INDEX IF EXISTS ingestion.idx_application_audit_entity_time;

CREATE INDEX idx_application_audit_event_type_recent
    ON ingestion.application_audit_event (event_type, occurred_at DESC, id DESC)
    WHERE event_type IS NOT NULL;
CREATE INDEX idx_application_audit_outcome_recent
    ON ingestion.application_audit_event (outcome, occurred_at DESC, id DESC)
    WHERE outcome IS NOT NULL;
CREATE INDEX idx_application_audit_actor_recent
    ON ingestion.application_audit_event (actor_subject, occurred_at DESC, id DESC)
    WHERE actor_subject IS NOT NULL;
CREATE INDEX idx_application_audit_run_recent
    ON ingestion.application_audit_event (run_id, occurred_at DESC, id DESC)
    WHERE run_id IS NOT NULL;
CREATE INDEX idx_application_audit_entity_recent
    ON ingestion.application_audit_event (entity_id, occurred_at DESC, id DESC)
    WHERE entity_id IS NOT NULL;
