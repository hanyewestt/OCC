-- VisitNumber is provider-supplied identity for one predicted bus. Existing retained rows predate this field.
ALTER TABLE ingestion.bus_arrival_observation
    ADD COLUMN visit_number SMALLINT;

-- The view selected observation.* and therefore depends on the removed column.
DROP VIEW ingestion.current_bus_arrival;

-- Explicitly removed because it has never been populated and is not a Bus Arrival provider field.
ALTER TABLE ingestion.bus_arrival_observation
    DROP COLUMN observed_at;

CREATE VIEW ingestion.current_bus_arrival AS
WITH latest_successful_run AS (
    SELECT DISTINCT ON (scope_key) id, scope_key
    FROM ingestion.poll_run
    WHERE outcome IN ('SUCCESS', 'PARTIAL')
    ORDER BY scope_key, completed_at DESC NULLS LAST, started_at DESC
)
SELECT observation.*,
       extract(epoch FROM (now() - observation.fetched_at))::bigint AS age_seconds
FROM latest_successful_run run
JOIN ingestion.bus_arrival_observation observation ON observation.poll_run_id = run.id
WHERE observation.classification = 'OBSERVATION_ONLY';
