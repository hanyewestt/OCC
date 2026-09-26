-- Read-only integration contract. No API call, credential or request header is exposed.
CREATE VIEW ingestion.bunching_snapshot AS
SELECT raw.id, raw.poll_run_id, raw.fetched_at, raw.source_key,
       raw.payload,
       COALESCE((SELECT jsonb_agg(to_jsonb(arrival) ORDER BY arrival.service_id, arrival.visit_order)
                 FROM ingestion.bus_arrival_observation arrival
                 WHERE arrival.raw_observation_id = raw.id), '[]'::jsonb) AS normalized_arrivals
FROM ingestion.raw_observation raw
JOIN ingestion.poll_run poll ON poll.id = raw.poll_run_id
WHERE raw.source = 'DATAMALL' AND raw.source_key LIKE 'stop:%'
  AND raw.classification = 'OBSERVATION_ONLY'
  AND raw.http_status BETWEEN 200 AND 299
  AND poll.outcome = 'SUCCESS';

CREATE VIEW ingestion.bunching_route_direction AS
WITH route AS (
    SELECT reference_version_id, service_id, operator, direction_id,
           array_agg(stop_code ORDER BY stop_sequence) AS stops
    FROM ingestion.bus_route_stop
    GROUP BY reference_version_id, service_id, operator, direction_id
)
SELECT route.reference_version_id, route.service_id, route.operator, route.direction_id,
       route.stops[1] AS origin_code, route.stops[array_length(route.stops, 1)] AS destination_code,
       stop.stop_code, count(*) AS stop_visits
FROM route
JOIN ingestion.reference_dataset_version version ON version.id = route.reference_version_id
CROSS JOIN LATERAL unnest(route.stops) AS stop(stop_code)
WHERE version.status = 'ACTIVE'
GROUP BY route.reference_version_id, route.service_id, route.operator, route.direction_id,
         route.stops, stop.stop_code;
