-- Stable, monotonic cursor for internal consumers. Existing observations receive a sequence on migration.
ALTER TABLE ingestion.raw_observation
    ADD COLUMN feed_sequence BIGSERIAL;

ALTER TABLE ingestion.raw_observation
    ALTER COLUMN feed_sequence SET NOT NULL;

CREATE UNIQUE INDEX uq_raw_observation_feed_sequence
    ON ingestion.raw_observation (feed_sequence);

CREATE INDEX ix_raw_observation_bunching_feed
    ON ingestion.raw_observation (feed_sequence)
    WHERE source = 'DATAMALL' AND source_key LIKE 'stop:%'
      AND classification = 'OBSERVATION_ONLY' AND http_status BETWEEN 200 AND 299;
