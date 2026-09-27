ALTER TABLE bunching.demo_alert_state
    ADD COLUMN pending_headway_seconds INTEGER,
    ADD COLUMN pending_detected_at TIMESTAMPTZ,
    ADD COLUMN pending_leading_observation_id UUID,
    ADD COLUMN pending_following_observation_id UUID,
    ADD COLUMN pending_raw_observation_id UUID,
    ADD COLUMN pending_poll_run_id UUID;

ALTER TABLE bunching.alert_event
    ADD COLUMN expected_headway_seconds INTEGER,
    ADD COLUMN first_observed_headway_seconds INTEGER,
    ADD COLUMN second_observed_headway_seconds INTEGER,
    ADD COLUMN first_confirming_at TIMESTAMPTZ,
    ADD COLUMN second_confirming_at TIMESTAMPTZ,
    ADD COLUMN first_leading_observation_id UUID,
    ADD COLUMN first_following_observation_id UUID,
    ADD COLUMN second_leading_observation_id UUID,
    ADD COLUMN second_following_observation_id UUID,
    ADD COLUMN detection_rule_version VARCHAR(80);

ALTER TABLE bunching.alert_event
    ADD CONSTRAINT ck_alert_event_confirmed_headways CHECK (
        expected_headway_seconds IS NULL OR expected_headway_seconds > 0),
    ADD CONSTRAINT ck_alert_event_confirmed_observed_headways CHECK (
        (first_observed_headway_seconds IS NULL AND second_observed_headway_seconds IS NULL) OR
        (first_observed_headway_seconds >= 0 AND second_observed_headway_seconds >= 0));

CREATE TABLE bunching.diagnosis_outbox (
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
    CONSTRAINT pk_diagnosis_outbox PRIMARY KEY (id),
    CONSTRAINT uq_diagnosis_outbox_event UNIQUE (event_id),
    CONSTRAINT fk_diagnosis_outbox_event FOREIGN KEY (event_id) REFERENCES bunching.alert_event (id),
    CONSTRAINT ck_diagnosis_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED_TERMINAL')),
    CONSTRAINT ck_diagnosis_outbox_attempts CHECK (attempt_count >= 0)
);
CREATE INDEX ix_diagnosis_outbox_claim ON bunching.diagnosis_outbox (status, next_attempt_at, lease_until, created_at);
