-- Phases 5-6: retain only current recovery, deduplication, selection and alert state.
CREATE TABLE bunching.source_processing_state (
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
    CONSTRAINT pk_source_processing_state PRIMARY KEY (raw_observation_id),
    CONSTRAINT ck_source_processing_state_status CHECK (status IN ('PROCESSED', 'DEFERRED', 'REJECTED')),
    CONSTRAINT ck_source_processing_state_deferred CHECK ((status <> 'DEFERRED') OR (next_attempt_at IS NOT NULL)),
    CONSTRAINT ck_source_processing_state_rejected CHECK ((status <> 'REJECTED') OR (rejected_at IS NOT NULL AND reason IS NOT NULL)),
    CONSTRAINT ck_source_processing_state_processed CHECK ((status <> 'PROCESSED') OR
        (processed_at IS NOT NULL AND evaluated_at IS NOT NULL AND threshold_seconds IS NOT NULL AND bunched_group_count IS NOT NULL))
);
CREATE INDEX ix_source_processing_state_due_retry
    ON bunching.source_processing_state (demo_run_id, next_attempt_at) WHERE status = 'DEFERRED';

INSERT INTO bunching.source_processing_state (raw_observation_id, status, processed_at, demo_run_id,
    evaluated_at, threshold_seconds, bunched_group_count)
SELECT raw_observation_id, 'PROCESSED', evaluated_at, demo_run_id, evaluated_at, threshold_seconds, bunched_group_count
FROM bunching.bunching_observation;
INSERT INTO bunching.source_processing_state (raw_observation_id, status, rejected_at, reason, demo_run_id)
SELECT raw_observation_id, 'REJECTED', rejected_at, reason, demo_run_id FROM bunching.source_rejection
ON CONFLICT (raw_observation_id) DO NOTHING;
INSERT INTO bunching.source_processing_state (raw_observation_id, status, next_attempt_at, demo_run_id)
SELECT raw_observation_id, 'DEFERRED', next_attempt_at, demo_run_id FROM bunching.source_retry
ON CONFLICT (raw_observation_id) DO NOTHING;

CREATE TABLE bunching.consumer_state (
    id INTEGER NOT NULL,
    mode VARCHAR(10) NOT NULL,
    demo_run_id UUID,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_consumer_state PRIMARY KEY (id),
    CONSTRAINT ck_consumer_state_singleton CHECK (id = 1),
    CONSTRAINT ck_consumer_state_mode CHECK (mode IN ('LIVE', 'DEMO')),
    CONSTRAINT ck_consumer_state_demo_run CHECK ((mode = 'LIVE' AND demo_run_id IS NULL) OR
        (mode = 'DEMO' AND demo_run_id IS NOT NULL)),
    CONSTRAINT ck_consumer_state_sequence CHECK (last_sequence >= 0)
);
INSERT INTO bunching.consumer_state (id, mode, demo_run_id, last_sequence, updated_at, version)
SELECT selection.id, selection.mode, selection.demo_run_id,
    CASE WHEN selection.mode = 'DEMO' AND cursor.demo_run_id IS DISTINCT FROM selection.demo_run_id THEN 0
         WHEN selection.mode = 'LIVE' AND cursor.demo_run_id IS NOT NULL THEN 0 ELSE cursor.last_sequence END,
    selection.changed_at, selection.version
FROM bunching.consumer_selection selection JOIN bunching.ingestion_feed_cursor cursor ON cursor.id = selection.id;

CREATE TABLE bunching.alert_processing_state (
    id UUID NOT NULL,
    demo_run_id UUID,
    service_id VARCHAR(40) NOT NULL,
    stop_code VARCHAR(20) NOT NULL,
    direction_id INTEGER NOT NULL,
    last_fetched_at TIMESTAMPTZ NOT NULL,
    active_alert_id UUID,
    leading_observation_id UUID,
    following_observation_id UUID,
    pending_headway_seconds INTEGER,
    pending_detected_at TIMESTAMPTZ,
    pending_leading_observation_id UUID,
    pending_following_observation_id UUID,
    pending_raw_observation_id UUID,
    pending_poll_run_id UUID,
    CONSTRAINT pk_alert_processing_state PRIMARY KEY (id),
    CONSTRAINT uq_alert_processing_state_scope UNIQUE NULLS NOT DISTINCT (demo_run_id, service_id, stop_code, direction_id)
);
INSERT INTO bunching.alert_processing_state (id, service_id, stop_code, direction_id, last_fetched_at, active_alert_id)
SELECT (substr(md5('live:' || service_id || ':' || stop_code || ':' || direction_id), 1, 8) || '-' ||
        substr(md5('live:' || service_id || ':' || stop_code || ':' || direction_id), 9, 4) || '-' ||
        substr(md5('live:' || service_id || ':' || stop_code || ':' || direction_id), 13, 4) || '-' ||
        substr(md5('live:' || service_id || ':' || stop_code || ':' || direction_id), 17, 4) || '-' ||
        substr(md5('live:' || service_id || ':' || stop_code || ':' || direction_id), 21, 12))::uuid,
       service_id, stop_code, direction_id, last_fetched_at, active_alert_id
FROM bunching.alert_state;
INSERT INTO bunching.alert_processing_state (id, demo_run_id, service_id, stop_code, direction_id, last_fetched_at,
    active_alert_id, leading_observation_id, following_observation_id, pending_headway_seconds, pending_detected_at,
    pending_leading_observation_id, pending_following_observation_id, pending_raw_observation_id, pending_poll_run_id)
SELECT (substr(md5('demo:' || demo_run_id || ':' || service_id || ':' || stop_code || ':' || direction_id), 1, 8) || '-' ||
        substr(md5('demo:' || demo_run_id || ':' || service_id || ':' || stop_code || ':' || direction_id), 9, 4) || '-' ||
        substr(md5('demo:' || demo_run_id || ':' || service_id || ':' || stop_code || ':' || direction_id), 13, 4) || '-' ||
        substr(md5('demo:' || demo_run_id || ':' || service_id || ':' || stop_code || ':' || direction_id), 17, 4) || '-' ||
        substr(md5('demo:' || demo_run_id || ':' || service_id || ':' || stop_code || ':' || direction_id), 21, 12))::uuid,
       demo_run_id, service_id, stop_code, direction_id, last_fetched_at, active_alert_id, leading_observation_id,
       following_observation_id, pending_headway_seconds, pending_detected_at, pending_leading_observation_id,
       pending_following_observation_id, pending_raw_observation_id, pending_poll_run_id
FROM bunching.demo_alert_state;

ALTER TABLE bunching.alert_event DROP COLUMN actor, DROP COLUMN feed_scope;
DROP TABLE bunching.bunching_observation, bunching.source_retry, bunching.source_rejection,
    bunching.consumer_selection, bunching.ingestion_feed_cursor, bunching.alert_state, bunching.demo_alert_state;
