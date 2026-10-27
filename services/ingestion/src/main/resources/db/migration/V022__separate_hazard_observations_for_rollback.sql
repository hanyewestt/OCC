CREATE TABLE ingestion.hazard_observation (
    id UUID NOT NULL,
    raw_observation_id UUID NOT NULL,
    poll_run_id UUID NOT NULL,
    source_incident_key CHAR(64) NOT NULL,
    incident_type VARCHAR(60) NOT NULL,
    message VARCHAR(500) NOT NULL,
    observed_at TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL,
    classification VARCHAR(30) NOT NULL,
    demo_run_id UUID,
    hazard_kind VARCHAR(30) NOT NULL,
    hazard_facts JSONB NOT NULL,
    CONSTRAINT pk_hazard_observation PRIMARY KEY (id),
    CONSTRAINT fk_hazard_observation_raw FOREIGN KEY (raw_observation_id)
        REFERENCES ingestion.raw_observation (id),
    CONSTRAINT fk_hazard_observation_poll FOREIGN KEY (poll_run_id)
        REFERENCES ingestion.poll_run (id),
    CONSTRAINT fk_hazard_observation_demo_run FOREIGN KEY (demo_run_id)
        REFERENCES ingestion.demo_run (id),
    CONSTRAINT uq_hazard_observation_source UNIQUE (raw_observation_id, source_incident_key),
    CONSTRAINT ck_hazard_observation_kind CHECK (hazard_kind IN ('FLOOD', 'RAIL_DISRUPTION', 'ROADWORKS')),
    CONSTRAINT ck_hazard_observation_facts CHECK (jsonb_typeof(hazard_facts) = 'object'),
    CONSTRAINT ck_hazard_observation_demo_scope CHECK (
        (classification = 'SIMULATION' AND demo_run_id IS NOT NULL) OR
        (classification = 'OBSERVATION_ONLY' AND demo_run_id IS NULL))
);
CREATE INDEX ix_hazard_observation_poll ON ingestion.hazard_observation (hazard_kind, poll_run_id);
CREATE INDEX ix_hazard_observation_demo_feed ON ingestion.hazard_observation (demo_run_id, fetched_at)
    WHERE classification = 'SIMULATION';

INSERT INTO ingestion.hazard_observation (
    id, raw_observation_id, poll_run_id, source_incident_key, incident_type, message, observed_at,
    fetched_at, ingested_at, classification, demo_run_id, hazard_kind, hazard_facts)
SELECT id, raw_observation_id, poll_run_id, source_incident_key, incident_type, message, observed_at,
       fetched_at, ingested_at, classification, demo_run_id, hazard_kind, hazard_facts
FROM ingestion.traffic_incident_observation
WHERE hazard_kind <> 'TRAFFIC_INCIDENT';

DELETE FROM ingestion.traffic_incident_observation WHERE hazard_kind <> 'TRAFFIC_INCIDENT';
ALTER TABLE ingestion.traffic_incident_observation DROP CONSTRAINT ck_traffic_incident_hazard;
ALTER TABLE ingestion.traffic_incident_observation ALTER COLUMN latitude SET NOT NULL;
ALTER TABLE ingestion.traffic_incident_observation ALTER COLUMN longitude SET NOT NULL;
DROP INDEX ingestion.ix_traffic_incident_hazard_poll;
ALTER TABLE ingestion.traffic_incident_observation DROP COLUMN hazard_facts;
ALTER TABLE ingestion.traffic_incident_observation DROP COLUMN hazard_kind;
