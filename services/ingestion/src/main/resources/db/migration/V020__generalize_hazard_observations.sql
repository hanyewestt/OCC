-- One normalized store; legacy traffic rows and IDs remain canonical.
ALTER TABLE ingestion.traffic_incident_observation ADD COLUMN hazard_kind VARCHAR(30) NOT NULL DEFAULT 'TRAFFIC_INCIDENT';
ALTER TABLE ingestion.traffic_incident_observation ADD COLUMN hazard_facts JSONB;
ALTER TABLE ingestion.traffic_incident_observation ALTER COLUMN latitude DROP NOT NULL;
ALTER TABLE ingestion.traffic_incident_observation ALTER COLUMN longitude DROP NOT NULL;
ALTER TABLE ingestion.traffic_incident_observation ADD CONSTRAINT ck_traffic_incident_hazard
    CHECK ((hazard_kind = 'TRAFFIC_INCIDENT' AND latitude IS NOT NULL AND longitude IS NOT NULL)
        OR (hazard_kind IN ('FLOOD','RAIL_DISRUPTION','ROADWORKS') AND hazard_facts IS NOT NULL
            AND jsonb_typeof(hazard_facts) = 'object'));
COMMENT ON COLUMN ingestion.traffic_incident_observation.hazard_facts IS
    'Typed normalized hazard facts and multiple source locations; null for legacy traffic rows.';
CREATE INDEX ix_traffic_incident_hazard_poll ON ingestion.traffic_incident_observation (hazard_kind, poll_run_id);
