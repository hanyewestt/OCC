CREATE TABLE ingestion.application_audit_event (
 id UUID CONSTRAINT pk_application_audit_event PRIMARY KEY,
 event_schema_version INTEGER NOT NULL DEFAULT 1,
 occurred_at TIMESTAMPTZ NOT NULL, recorded_at TIMESTAMPTZ NOT NULL,
 category VARCHAR(40) NOT NULL, event_type VARCHAR(80) NOT NULL, outcome VARCHAR(30) NOT NULL,
 source_service VARCHAR(30) NOT NULL, actor_subject VARCHAR(255), actor_type VARCHAR(30) NOT NULL,
 entity_type VARCHAR(80) NOT NULL, entity_id VARCHAR(255) NOT NULL,
 run_id UUID, bus_id VARCHAR(255), captain_id UUID, correlation_id VARCHAR(255),
 summary VARCHAR(500) NOT NULL, details JSONB NOT NULL,
 source_key VARCHAR(255) CONSTRAINT uq_application_audit_event_source_key UNIQUE NOT NULL
);
-- Immutable event snapshots have no operational cascading foreign keys.
CREATE INDEX ix_application_audit_event_time ON ingestion.application_audit_event(occurred_at DESC,id DESC);
CREATE INDEX ix_application_audit_event_category_time ON ingestion.application_audit_event(category,occurred_at DESC,id DESC);
