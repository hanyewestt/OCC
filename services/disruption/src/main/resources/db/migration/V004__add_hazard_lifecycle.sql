ALTER TABLE disruption.disruption DROP CONSTRAINT ck_disruption_kind;
ALTER TABLE disruption.disruption ADD CONSTRAINT ck_disruption_kind
    CHECK (kind IN ('BUNCHING','TRAFFIC_INCIDENT','FLOOD','RAIL_DISRUPTION','ROADWORKS'));
ALTER TABLE disruption.disruption DROP CONSTRAINT ck_disruption_status;
ALTER TABLE disruption.disruption ADD CONSTRAINT ck_disruption_status CHECK (status IN ('ACTIVE','SCHEDULED','CLEARED'));
ALTER TABLE disruption.disruption DROP CONSTRAINT ck_disruption_lifecycle;
ALTER TABLE disruption.disruption ADD CONSTRAINT ck_disruption_lifecycle CHECK (
    (status IN ('ACTIVE','SCHEDULED') AND cleared_at IS NULL)
    OR (status='CLEARED' AND cleared_at IS NOT NULL AND cleared_at >= opened_at));
ALTER TABLE disruption.disruption_event DROP CONSTRAINT ck_disruption_event_kind;
ALTER TABLE disruption.disruption_event ADD CONSTRAINT ck_disruption_event_kind
    CHECK (kind IN ('BUNCHING','TRAFFIC_INCIDENT','FLOOD','RAIL_DISRUPTION','ROADWORKS'));
ALTER TABLE disruption.disruption_event DROP CONSTRAINT ck_disruption_event_kind_fields;
ALTER TABLE disruption.disruption_event ADD CONSTRAINT ck_disruption_event_kind_fields CHECK (
    (kind='BUNCHING' AND raw_observation_id IS NOT NULL AND poll_run_id IS NOT NULL AND source_observation_id IS NULL
        AND service_id IS NOT NULL AND stop_code IS NOT NULL AND direction_id IS NOT NULL)
    OR (kind IN ('TRAFFIC_INCIDENT','FLOOD','RAIL_DISRUPTION','ROADWORKS') AND source_observation_id IS NOT NULL
        AND poll_run_id IS NOT NULL AND service_id IS NULL AND stop_code IS NULL AND direction_id IS NULL
        AND expected_headway_seconds IS NULL AND first_observed_headway_seconds IS NULL AND second_observed_headway_seconds IS NULL));
CREATE TABLE disruption.hazard_checkpoint (
    kind VARCHAR(30) NOT NULL,
    completed_at TIMESTAMPTZ,
    poll_run_id UUID,
    CONSTRAINT pk_hazard_checkpoint PRIMARY KEY (kind),
    CONSTRAINT ck_hazard_checkpoint_cursor CHECK ((completed_at IS NULL) = (poll_run_id IS NULL))
);
INSERT INTO disruption.hazard_checkpoint (kind) VALUES ('FLOOD'),('RAIL_DISRUPTION'),('ROADWORKS');
CREATE TABLE disruption.hazard_state (
    id UUID NOT NULL, disruption_id UUID NOT NULL, source VARCHAR(50) NOT NULL, scope VARCHAR(50) NOT NULL,
    kind VARCHAR(30) NOT NULL, source_event_key VARCHAR(300) NOT NULL, classification VARCHAR(30) NOT NULL,
    demo_run_id UUID, status VARCHAR(20) NOT NULL, inactive_reason VARCHAR(40),
    opened_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, last_seen_at TIMESTAMPTZ NOT NULL,
    first_absent_at TIMESTAMPTZ, absence_count INTEGER NOT NULL DEFAULT 0,
    source_observation_id UUID NOT NULL, facts JSONB NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_hazard_state PRIMARY KEY (id),
    CONSTRAINT fk_hazard_state_disruption FOREIGN KEY (disruption_id) REFERENCES disruption.disruption(id),
    CONSTRAINT uq_hazard_state_source UNIQUE NULLS NOT DISTINCT (source,scope,classification,demo_run_id,source_event_key),
    CONSTRAINT ck_hazard_state_scope CHECK ((classification='SIMULATION' AND demo_run_id IS NOT NULL)
        OR (classification='OBSERVATION_ONLY' AND demo_run_id IS NULL)),
    CONSTRAINT ck_hazard_state_status CHECK (status IN ('ACTIVE','SCHEDULED','CLEARED')),
    CONSTRAINT ck_hazard_state_absence CHECK (absence_count >= 0),
    CONSTRAINT ck_hazard_state_facts CHECK (jsonb_typeof(facts)='object')
);
COMMENT ON COLUMN disruption.hazard_state.first_absent_at IS 'Null until a complete scoped snapshot omits the hazard.';
COMMENT ON COLUMN disruption.hazard_state.inactive_reason IS 'Null for current hazards; absence is not verified clearance.';
CREATE INDEX ix_hazard_state_status ON disruption.hazard_state(status,updated_at);
