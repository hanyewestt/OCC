CREATE TABLE bunching.consumer_selection (
    id INTEGER NOT NULL,
    mode VARCHAR(10) NOT NULL,
    demo_run_id UUID,
    changed_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_consumer_selection PRIMARY KEY (id),
    CONSTRAINT ck_consumer_selection_singleton CHECK (id = 1),
    CONSTRAINT ck_consumer_selection_mode CHECK (mode IN ('LIVE', 'DEMO')),
    CONSTRAINT ck_consumer_selection_demo_run CHECK (
        (mode = 'LIVE' AND demo_run_id IS NULL) OR (mode = 'DEMO' AND demo_run_id IS NOT NULL))
);
INSERT INTO bunching.consumer_selection (id, mode, changed_at) VALUES (1, 'LIVE', NOW());

CREATE TABLE bunching.demo_alert_state (
    demo_run_id UUID NOT NULL,
    service_id VARCHAR(40) NOT NULL,
    stop_code VARCHAR(20) NOT NULL,
    direction_id INTEGER NOT NULL,
    last_fetched_at TIMESTAMPTZ NOT NULL,
    active_alert_id UUID,
    CONSTRAINT pk_demo_alert_state PRIMARY KEY (demo_run_id, service_id, stop_code, direction_id)
);
