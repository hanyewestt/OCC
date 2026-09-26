ALTER TABLE ingestion.demo_run DROP CONSTRAINT ck_demo_run_status;
ALTER TABLE ingestion.demo_run ADD CONSTRAINT ck_demo_run_status
    CHECK (status IN ('PREPARING', 'RUNNING', 'REPLAY_COMPLETE', 'CLOSED', 'FAILED'));

DROP INDEX ingestion.uq_demo_run_active;
CREATE UNIQUE INDEX uq_demo_run_active ON ingestion.demo_run ((1))
    WHERE status IN ('PREPARING', 'RUNNING', 'REPLAY_COMPLETE');

-- This view used observation.* before the demo columns existed. Drop/recreate it
-- so those new columns are appended without changing the position of age_seconds.
DROP VIEW ingestion.current_bus_arrival;
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
