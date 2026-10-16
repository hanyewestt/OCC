CREATE TABLE disruption.source_processing_state (
    raw_observation_id UUID NOT NULL,
    status VARCHAR(10) NOT NULL,
    next_attempt_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    reason VARCHAR(100),
    demo_run_id UUID,
    evaluated_at TIMESTAMPTZ,
    threshold_seconds NUMERIC(18, 9),
    bunched_group_count INTEGER,
    source_sequence BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_source_processing_state PRIMARY KEY (raw_observation_id),
    CONSTRAINT ck_source_processing_status CHECK (status IN ('PROCESSED', 'DEFERRED', 'REJECTED')),
    CONSTRAINT ck_source_processing_deferred CHECK (status <> 'DEFERRED' OR next_attempt_at IS NOT NULL),
    CONSTRAINT ck_source_processing_rejected CHECK (status <> 'REJECTED' OR (rejected_at IS NOT NULL AND reason IS NOT NULL)),
    CONSTRAINT ck_source_processing_processed CHECK (status <> 'PROCESSED' OR
        (processed_at IS NOT NULL AND evaluated_at IS NOT NULL AND threshold_seconds IS NOT NULL AND bunched_group_count IS NOT NULL)),
    CONSTRAINT ck_source_processing_count CHECK (bunched_group_count IS NULL OR bunched_group_count >= 0),
    CONSTRAINT ck_source_processing_threshold CHECK (threshold_seconds IS NULL OR threshold_seconds > 0),
    CONSTRAINT ck_source_processing_sequence CHECK (source_sequence IS NULL OR source_sequence >= 0)
);
CREATE INDEX ix_source_processing_state_due_retry
    ON disruption.source_processing_state (demo_run_id, next_attempt_at) WHERE status = 'DEFERRED';

CREATE TABLE disruption.consumer_state (
    id INTEGER NOT NULL,
    mode VARCHAR(10) NOT NULL,
    demo_run_id UUID,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_consumer_state PRIMARY KEY (id),
    CONSTRAINT ck_consumer_state_singleton CHECK (id = 1),
    CONSTRAINT ck_consumer_state_mode CHECK (mode IN ('LIVE', 'DEMO')),
    CONSTRAINT ck_consumer_state_demo_run CHECK ((mode = 'LIVE' AND demo_run_id IS NULL) OR (mode = 'DEMO' AND demo_run_id IS NOT NULL)),
    CONSTRAINT ck_consumer_state_sequence CHECK (last_sequence >= 0)
);
CREATE TABLE disruption.disruption (
    id UUID NOT NULL,
    kind VARCHAR(30) NOT NULL,
    classification VARCHAR(30) NOT NULL,
    demo_run_id UUID,
    status VARCHAR(20) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    cleared_at TIMESTAMPTZ,
    CONSTRAINT pk_disruption PRIMARY KEY (id),
    CONSTRAINT ck_disruption_kind CHECK (kind IN ('BUNCHING', 'TRAFFIC_INCIDENT')),
    CONSTRAINT ck_disruption_classification CHECK (classification IN ('OBSERVATION_ONLY', 'SIMULATION')),
    CONSTRAINT ck_disruption_demo_scope CHECK ((classification = 'OBSERVATION_ONLY' AND demo_run_id IS NULL)
        OR (classification = 'SIMULATION' AND demo_run_id IS NOT NULL)),
    CONSTRAINT ck_disruption_status CHECK (status IN ('ACTIVE', 'CLEARED')),
    CONSTRAINT ck_disruption_lifecycle CHECK ((status = 'ACTIVE' AND cleared_at IS NULL)
        OR (status = 'CLEARED' AND cleared_at IS NOT NULL AND cleared_at >= opened_at))
);
CREATE INDEX ix_disruption_status_kind ON disruption.disruption (status, kind, updated_at DESC);
CREATE INDEX ix_disruption_demo_scope ON disruption.disruption (demo_run_id, updated_at DESC)
    WHERE classification = 'SIMULATION';

CREATE TABLE disruption.disruption_processing_state (
    id UUID NOT NULL,
    demo_run_id UUID,
    service_id VARCHAR(40) NOT NULL,
    stop_code VARCHAR(20) NOT NULL,
    direction_id INTEGER NOT NULL,
    last_fetched_at TIMESTAMPTZ NOT NULL,
    active_disruption_id UUID,
    leading_observation_id UUID,
    following_observation_id UUID,
    pending_headway_seconds INTEGER,
    pending_detected_at TIMESTAMPTZ,
    pending_leading_observation_id UUID,
    pending_following_observation_id UUID,
    pending_raw_observation_id UUID,
    pending_poll_run_id UUID,
    CONSTRAINT pk_disruption_processing_state PRIMARY KEY (id),
    CONSTRAINT fk_disruption_processing_active FOREIGN KEY (active_disruption_id) REFERENCES disruption.disruption (id),
    CONSTRAINT uq_disruption_processing_scope UNIQUE NULLS NOT DISTINCT (demo_run_id, service_id, stop_code, direction_id)
);

CREATE TABLE disruption.disruption_event (
    id UUID NOT NULL,
    disruption_id UUID NOT NULL,
    kind VARCHAR(30) NOT NULL,
    action VARCHAR(20) NOT NULL,
    classification VARCHAR(30) NOT NULL,
    service_id VARCHAR(40),
    stop_code VARCHAR(20),
    direction_id INTEGER,
    raw_observation_id UUID,
    poll_run_id UUID,
    source_observation_id UUID,
    fetched_at TIMESTAMPTZ NOT NULL,
    triggered_at TIMESTAMPTZ NOT NULL,
    evidence JSONB NOT NULL,
    demo_run_id UUID,
    leading_observation_id UUID,
    following_observation_id UUID,
    expected_headway_seconds INTEGER,
    first_observed_headway_seconds INTEGER,
    second_observed_headway_seconds INTEGER,
    first_confirming_at TIMESTAMPTZ,
    second_confirming_at TIMESTAMPTZ,
    first_leading_observation_id UUID,
    first_following_observation_id UUID,
    second_leading_observation_id UUID,
    second_following_observation_id UUID,
    detection_rule_version VARCHAR(80),
    CONSTRAINT pk_disruption_event PRIMARY KEY (id),
    CONSTRAINT fk_disruption_event_parent FOREIGN KEY (disruption_id) REFERENCES disruption.disruption (id),
    CONSTRAINT ck_disruption_event_kind CHECK (kind IN ('BUNCHING', 'TRAFFIC_INCIDENT')),
    CONSTRAINT ck_disruption_event_action CHECK (action IN ('OPENED', 'UPDATED', 'CLEARED')),
    CONSTRAINT ck_disruption_event_classification CHECK (classification IN ('OBSERVATION_ONLY', 'SIMULATION')),
    CONSTRAINT ck_disruption_event_scope CHECK ((classification = 'OBSERVATION_ONLY' AND demo_run_id IS NULL)
        OR (classification = 'SIMULATION' AND demo_run_id IS NOT NULL)),
    CONSTRAINT ck_disruption_event_evidence CHECK (jsonb_typeof(evidence) = 'object'),
    CONSTRAINT ck_disruption_event_headway CHECK (expected_headway_seconds IS NULL OR expected_headway_seconds > 0),
    CONSTRAINT ck_disruption_event_observed_headways CHECK (
        (first_observed_headway_seconds IS NULL AND second_observed_headway_seconds IS NULL)
        OR (first_observed_headway_seconds >= 0 AND second_observed_headway_seconds >= 0)),
    CONSTRAINT ck_disruption_event_kind_fields CHECK (
        (kind = 'BUNCHING' AND raw_observation_id IS NOT NULL AND poll_run_id IS NOT NULL AND source_observation_id IS NULL
            AND service_id IS NOT NULL AND stop_code IS NOT NULL AND direction_id IS NOT NULL)
        OR (kind = 'TRAFFIC_INCIDENT' AND source_observation_id IS NOT NULL AND poll_run_id IS NOT NULL
            AND service_id IS NULL AND stop_code IS NULL AND direction_id IS NULL
            AND expected_headway_seconds IS NULL AND first_observed_headway_seconds IS NULL
            AND second_observed_headway_seconds IS NULL)),
    CONSTRAINT ck_disruption_event_bunching_action CHECK (kind <> 'BUNCHING' OR action IN ('OPENED', 'CLEARED'))
);
CREATE INDEX ix_disruption_event_demo_run ON disruption.disruption_event (demo_run_id, triggered_at)
    WHERE classification = 'SIMULATION';
CREATE INDEX ix_disruption_event_poll_run ON disruption.disruption_event (poll_run_id);
CREATE INDEX ix_disruption_event_triggered_at ON disruption.disruption_event (triggered_at);

CREATE TABLE disruption.event_evidence (
    event_id UUID NOT NULL,
    evidence_kind VARCHAR(30) NOT NULL,
    source_id UUID NOT NULL,
    evidence_snapshot JSONB NOT NULL,
    evidence_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_event_evidence PRIMARY KEY (event_id, evidence_kind, source_id),
    CONSTRAINT fk_event_evidence_event FOREIGN KEY (event_id) REFERENCES disruption.disruption_event (id),
    CONSTRAINT ck_event_evidence_kind CHECK (evidence_kind IN ('BUS_ARRIVAL', 'TRAFFIC_INCIDENT')),
    CONSTRAINT ck_event_evidence_snapshot CHECK (jsonb_typeof(evidence_snapshot) = 'object')
);
CREATE INDEX ix_event_evidence_source ON disruption.event_evidence (evidence_kind, source_id);

CREATE TABLE disruption.traffic_incident_state (
    id UUID NOT NULL,
    classification VARCHAR(30) NOT NULL,
    demo_run_id UUID,
    source VARCHAR(40) NOT NULL,
    source_incident_key CHAR(64) NOT NULL,
    incident_type VARCHAR(60) NOT NULL,
    message VARCHAR(500) NOT NULL,
    latitude NUMERIC(10,7) NOT NULL,
    longitude NUMERIC(10,7) NOT NULL,
    observed_at TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ NOT NULL,
    source_observation_id UUID NOT NULL,
    last_poll_run_id UUID NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    absence_count INTEGER NOT NULL DEFAULT 0,
    opened_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    cleared_at TIMESTAMPTZ,
    disruption_id UUID NOT NULL,
    last_processed_poll_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_traffic_incident_state PRIMARY KEY (id),
    CONSTRAINT fk_traffic_incident_disruption FOREIGN KEY (disruption_id) REFERENCES disruption.disruption (id),
    CONSTRAINT uq_traffic_incident_identity UNIQUE NULLS NOT DISTINCT (classification, demo_run_id, source, source_incident_key),
    CONSTRAINT ck_traffic_incident_scope CHECK ((classification = 'OBSERVATION_ONLY' AND demo_run_id IS NULL)
        OR (classification = 'SIMULATION' AND demo_run_id IS NOT NULL)),
    CONSTRAINT ck_traffic_incident_status CHECK (status IN ('ACTIVE', 'CLEARED')),
    CONSTRAINT ck_traffic_incident_absence CHECK (absence_count >= 0),
    CONSTRAINT ck_traffic_incident_location CHECK (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_traffic_incident_lifecycle CHECK ((status = 'ACTIVE' AND cleared_at IS NULL)
        OR (status = 'CLEARED' AND cleared_at IS NOT NULL AND cleared_at >= opened_at))
);
CREATE INDEX ix_traffic_incident_state_due ON disruption.traffic_incident_state (status, fetched_at);

CREATE TABLE disruption.traffic_poll_checkpoint (
    id INTEGER NOT NULL,
    completed_at TIMESTAMPTZ,
    poll_run_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_traffic_poll_checkpoint PRIMARY KEY (id),
    CONSTRAINT ck_traffic_poll_checkpoint_singleton CHECK (id = 1)
);
INSERT INTO disruption.traffic_poll_checkpoint (id, completed_at, poll_run_id, version) VALUES (1, NULL, NULL, 0);

CREATE TABLE disruption.planning_outbox (
    id UUID NOT NULL,
    event_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    last_http_status INTEGER,
    failure_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    payload_json TEXT,
    payload_fingerprint CHAR(64),
    schema_version VARCHAR(80),
    CONSTRAINT pk_planning_outbox PRIMARY KEY (id),
    CONSTRAINT fk_planning_outbox_event FOREIGN KEY (event_id) REFERENCES disruption.disruption_event (id),
    CONSTRAINT uq_planning_outbox_event UNIQUE (event_id),
    CONSTRAINT ck_planning_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED_TERMINAL')),
    CONSTRAINT ck_planning_outbox_attempts CHECK (attempt_count >= 0)
);
CREATE INDEX ix_planning_outbox_claim ON disruption.planning_outbox (status, next_attempt_at, lease_until, created_at);

CREATE TABLE disruption.legacy_import_ledger (
    source_schema VARCHAR(63) NOT NULL,
    source_table VARCHAR(63) NOT NULL,
    source_primary_key TEXT NOT NULL,
    target_primary_key TEXT NOT NULL,
    source_row_hash CHAR(64) NOT NULL,
    import_version VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_disruption_legacy_import_ledger PRIMARY KEY (source_schema, source_table, source_primary_key),
    CONSTRAINT ck_disruption_import_status CHECK (status = 'APPLIED')
);
