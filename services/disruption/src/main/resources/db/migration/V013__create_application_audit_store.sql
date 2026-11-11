CREATE TABLE disruption.application_audit_metadata (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    collection_started_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE disruption.application_audit_event (
    id UUID PRIMARY KEY,
    event_schema_version INTEGER NOT NULL DEFAULT 1,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    category VARCHAR(40) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    source_service VARCHAR(30) NOT NULL DEFAULT 'DISRUPTION',
    actor_subject VARCHAR(255),
    actor_type VARCHAR(30) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    run_id UUID,
    bus_id VARCHAR(255),
    captain_id UUID,
    correlation_id VARCHAR(255),
    summary VARCHAR(500) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_key VARCHAR(255) NOT NULL UNIQUE,
    backfilled BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX ix_disruption_audit_time ON disruption.application_audit_event(occurred_at DESC, id DESC);
CREATE INDEX ix_disruption_audit_category_time ON disruption.application_audit_event(category, occurred_at DESC, id DESC);
CREATE INDEX ix_disruption_audit_type_time ON disruption.application_audit_event(event_type, occurred_at DESC, id DESC);
CREATE INDEX ix_disruption_audit_actor_time ON disruption.application_audit_event(actor_subject, occurred_at DESC, id DESC);
CREATE INDEX ix_disruption_audit_run_time ON disruption.application_audit_event(run_id, occurred_at DESC, id DESC);
CREATE INDEX ix_disruption_audit_entity_time ON disruption.application_audit_event(entity_id, occurred_at DESC, id DESC);
