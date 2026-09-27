CREATE TABLE ingestion.demo_run (
    id UUID NOT NULL,
    scenario_key VARCHAR(80) NOT NULL,
    scenario_version VARCHAR(40) NOT NULL,
    seed BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    current_step INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    failure_reason VARCHAR(500),
    classification VARCHAR(30) NOT NULL DEFAULT 'SIMULATION',
    CONSTRAINT pk_demo_run PRIMARY KEY (id),
    CONSTRAINT ck_demo_run_status CHECK (status IN ('RUNNING', 'REPLAY_COMPLETE', 'CLOSED', 'FAILED')),
    CONSTRAINT ck_demo_run_step CHECK (current_step >= 0),
    CONSTRAINT ck_demo_run_classification CHECK (classification = 'SIMULATION')
);
CREATE UNIQUE INDEX uq_demo_run_active ON ingestion.demo_run ((1))
    WHERE status IN ('RUNNING', 'REPLAY_COMPLETE');

CREATE TABLE ingestion.demo_vehicle_assignment (
    demo_run_id UUID NOT NULL,
    trip_id VARCHAR(80) NOT NULL,
    service_id VARCHAR(20) NOT NULL,
    direction_id SMALLINT NOT NULL,
    operator VARCHAR(40) NOT NULL,
    vehicle_registration_number VARCHAR(20) NOT NULL,
    vehicle_type VARCHAR(10) NOT NULL,
    captain_code VARCHAR(40) NOT NULL,
    CONSTRAINT pk_demo_vehicle_assignment PRIMARY KEY (demo_run_id, trip_id),
    CONSTRAINT fk_demo_vehicle_assignment_run FOREIGN KEY (demo_run_id) REFERENCES ingestion.demo_run (id),
    CONSTRAINT uq_demo_vehicle_assignment_registration UNIQUE (demo_run_id, vehicle_registration_number),
    CONSTRAINT uq_demo_vehicle_assignment_captain UNIQUE (demo_run_id, captain_code),
    CONSTRAINT ck_demo_vehicle_assignment_direction CHECK (direction_id > 0)
);

ALTER TABLE ingestion.poll_run ADD COLUMN classification VARCHAR(30) NOT NULL DEFAULT 'OBSERVATION_ONLY';
ALTER TABLE ingestion.poll_run ADD COLUMN demo_run_id UUID;
ALTER TABLE ingestion.poll_run ADD CONSTRAINT fk_poll_run_demo_run FOREIGN KEY (demo_run_id) REFERENCES ingestion.demo_run (id);

ALTER TABLE ingestion.raw_observation ADD COLUMN demo_run_id UUID;
ALTER TABLE ingestion.raw_observation ADD CONSTRAINT fk_raw_observation_demo_run FOREIGN KEY (demo_run_id) REFERENCES ingestion.demo_run (id);
ALTER TABLE ingestion.raw_observation ADD CONSTRAINT ck_raw_observation_demo_scope CHECK (
    (classification = 'SIMULATION' AND demo_run_id IS NOT NULL) OR
    (classification = 'OBSERVATION_ONLY' AND demo_run_id IS NULL));

ALTER TABLE ingestion.bus_arrival_observation ADD COLUMN classification VARCHAR(30) NOT NULL DEFAULT 'OBSERVATION_ONLY';
ALTER TABLE ingestion.bus_arrival_observation ADD COLUMN demo_run_id UUID;
ALTER TABLE ingestion.bus_arrival_observation ADD COLUMN trip_id VARCHAR(80);
ALTER TABLE ingestion.bus_arrival_observation ADD CONSTRAINT fk_bus_arrival_demo_assignment
    FOREIGN KEY (demo_run_id, trip_id) REFERENCES ingestion.demo_vehicle_assignment (demo_run_id, trip_id);
ALTER TABLE ingestion.bus_arrival_observation ADD CONSTRAINT ck_bus_arrival_demo_scope CHECK (
    (classification = 'SIMULATION' AND demo_run_id IS NOT NULL AND trip_id IS NOT NULL) OR
    (classification = 'OBSERVATION_ONLY' AND demo_run_id IS NULL AND trip_id IS NULL));
CREATE INDEX ix_bus_arrival_demo_feed ON ingestion.bus_arrival_observation (demo_run_id, fetched_at)
    WHERE classification = 'SIMULATION';

CREATE TABLE ingestion.traffic_incident_observation (
    id UUID NOT NULL,
    raw_observation_id UUID NOT NULL,
    poll_run_id UUID NOT NULL,
    source_incident_key CHAR(64) NOT NULL,
    incident_type VARCHAR(60) NOT NULL,
    latitude NUMERIC(10,7) NOT NULL,
    longitude NUMERIC(10,7) NOT NULL,
    message VARCHAR(500) NOT NULL,
    observed_at TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL,
    classification VARCHAR(30) NOT NULL,
    demo_run_id UUID,
    CONSTRAINT pk_traffic_incident_observation PRIMARY KEY (id),
    CONSTRAINT fk_traffic_incident_raw FOREIGN KEY (raw_observation_id) REFERENCES ingestion.raw_observation (id),
    CONSTRAINT fk_traffic_incident_poll FOREIGN KEY (poll_run_id) REFERENCES ingestion.poll_run (id),
    CONSTRAINT fk_traffic_incident_demo_run FOREIGN KEY (demo_run_id) REFERENCES ingestion.demo_run (id),
    CONSTRAINT uq_traffic_incident_observation_source UNIQUE (raw_observation_id, source_incident_key),
    CONSTRAINT ck_traffic_incident_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_traffic_incident_longitude CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_traffic_incident_demo_scope CHECK (
        (classification = 'SIMULATION' AND demo_run_id IS NOT NULL) OR
        (classification = 'OBSERVATION_ONLY' AND demo_run_id IS NULL))
);
CREATE INDEX ix_traffic_incident_demo_feed ON ingestion.traffic_incident_observation (demo_run_id, fetched_at)
    WHERE classification = 'SIMULATION';
