-- One current episode per service/stop/direction, independent of source telemetry retention.
CREATE TABLE bunching.alert_state (
    service_id VARCHAR(40) NOT NULL,
    stop_code VARCHAR(20) NOT NULL,
    direction_id INTEGER NOT NULL,
    last_fetched_at TIMESTAMPTZ NOT NULL,
    active_alert_id UUID, -- Absent until bunching opens an episode, and after it clears.
    CONSTRAINT pk_alert_state PRIMARY KEY (service_id, stop_code, direction_id)
);

-- Durable delivery source and audit history. NOTIFY is only a commit-time wake-up signal.
CREATE TABLE bunching.alert_event (
    id UUID NOT NULL,
    alert_id UUID NOT NULL,
    action VARCHAR(20) NOT NULL,
    actor VARCHAR(80) NOT NULL DEFAULT 'SYSTEM:BUS_BUNCHING_DETECTION',
    classification VARCHAR(30) NOT NULL DEFAULT 'OBSERVATION_ONLY',
    service_id VARCHAR(40) NOT NULL,
    stop_code VARCHAR(20) NOT NULL,
    direction_id INTEGER NOT NULL,
    raw_observation_id UUID NOT NULL,
    poll_run_id UUID NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    triggered_at TIMESTAMPTZ NOT NULL,
    evidence JSONB NOT NULL,
    CONSTRAINT pk_alert_event PRIMARY KEY (id),
    CONSTRAINT uq_alert_event_episode_action UNIQUE (alert_id, action),
    CONSTRAINT ck_alert_event_action CHECK (action IN ('OPENED', 'CLEARED')),
    CONSTRAINT ck_alert_event_classification CHECK (classification = 'OBSERVATION_ONLY'),
    CONSTRAINT ck_alert_event_evidence CHECK (jsonb_typeof(evidence) = 'object')
);
CREATE INDEX ix_alert_event_triggered_at ON bunching.alert_event (triggered_at);
CREATE INDEX ix_alert_event_poll_run_id ON bunching.alert_event (poll_run_id);
