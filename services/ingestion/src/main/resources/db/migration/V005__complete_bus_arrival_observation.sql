alter table ingestion.bus_arrival_observation
    add column origin_code varchar(12),
    add column destination_code varchar(12),
    add column monitored boolean,
    add column vehicle_type varchar(10);

create index ix_bus_arrival_estimated_arrival
    on ingestion.bus_arrival_observation (estimated_arrival_at);

create index ix_bus_arrival_raw_observation
    on ingestion.bus_arrival_observation (raw_observation_id);

create index ix_bus_arrival_poll_run
    on ingestion.bus_arrival_observation (poll_run_id);
