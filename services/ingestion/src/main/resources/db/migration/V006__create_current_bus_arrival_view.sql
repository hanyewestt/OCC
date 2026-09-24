create view ingestion.current_bus_arrival as
with latest_successful_run as (
    select distinct on (scope_key) id, scope_key
    from ingestion.poll_run
    where outcome in ('SUCCESS', 'PARTIAL')
    order by scope_key, completed_at desc nulls last, started_at desc
)
select observation.*,
       extract(epoch from (now() - observation.fetched_at))::bigint as age_seconds
from latest_successful_run run
join ingestion.bus_arrival_observation observation on observation.poll_run_id = run.id;
