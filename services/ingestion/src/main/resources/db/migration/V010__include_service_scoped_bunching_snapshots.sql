-- V009 introduced service-scoped poll targets. Keep legacy snapshots readable
-- while exposing new service-scoped snapshots through the same read contract.
CREATE OR REPLACE VIEW ingestion.bunching_snapshot AS
SELECT raw.id, raw.poll_run_id, raw.fetched_at, raw.source_key,
       raw.payload,
       COALESCE((SELECT jsonb_agg(to_jsonb(arrival) ORDER BY arrival.service_id, arrival.visit_order)
                 FROM ingestion.bus_arrival_observation arrival
                 WHERE arrival.raw_observation_id = raw.id), '[]'::jsonb) AS normalized_arrivals
FROM ingestion.raw_observation raw
JOIN ingestion.poll_run poll ON poll.id = raw.poll_run_id
WHERE raw.source = 'DATAMALL'
  AND (raw.source_key LIKE 'stop:%' OR raw.source_key LIKE 'service:%:stop:%')
  AND raw.classification = 'OBSERVATION_ONLY'
  AND raw.http_status BETWEEN 200 AND 299
  AND poll.outcome = 'SUCCESS';
