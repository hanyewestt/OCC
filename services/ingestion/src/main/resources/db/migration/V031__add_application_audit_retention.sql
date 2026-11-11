ALTER TABLE ingestion.application_audit_event
    ADD COLUMN backfilled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE ingestion.application_audit_metadata (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    collection_started_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_application_audit_event_type_time
    ON ingestion.application_audit_event (event_type, occurred_at DESC, id DESC);
CREATE INDEX idx_application_audit_actor_time
    ON ingestion.application_audit_event (actor_subject, occurred_at DESC, id DESC);
CREATE INDEX idx_application_audit_run_time
    ON ingestion.application_audit_event (run_id, occurred_at DESC, id DESC);
CREATE INDEX idx_application_audit_entity_time
    ON ingestion.application_audit_event (entity_id, occurred_at DESC, id DESC);
