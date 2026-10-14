-- Poll-run ID is the correlation ID; raw-observation ID is the affected entity.
-- Defaults preserve compatibility with the previous recorder during rolling deployment.
ALTER TABLE bunching.bunching_observation
    ADD COLUMN actor VARCHAR(80) NOT NULL DEFAULT 'SYSTEM:BUS_BUNCHING_DETECTION',
    ADD COLUMN action VARCHAR(40) NOT NULL DEFAULT 'HEADWAY_EVALUATED';
