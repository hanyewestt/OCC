ALTER TABLE disruption.traffic_incident_state
    ADD COLUMN source_revision BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_traffic_incident_source_revision CHECK (source_revision > 0);
