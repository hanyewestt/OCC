-- Preserve legacy targets for audit, but prevent further unfiltered provider requests.
alter table ingestion.poll_target
    add column service_id varchar(20);

update ingestion.poll_target
set enabled = false
where service_id is null;
