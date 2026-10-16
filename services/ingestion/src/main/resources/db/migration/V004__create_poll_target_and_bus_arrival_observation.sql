-- Plan 3 "Database schema" — polling configuration and the normalized
-- Bus Arrival v3 observation. direction_id is nullable on purpose:
-- "Downstream components must treat null direction as insufficient evidence
-- rather than guessing."

create table ingestion.poll_target (
    source                varchar(40)  not null,
    scope_key             varchar(120) not null,
    stop_code             varchar(12),
    enabled               boolean      not null default true,
    poll_interval_seconds integer      not null default 60,
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,

    constraint pk_poll_target primary key (source, scope_key)
    -- stop_code intentionally has NO foreign key to bus_stop: targets outlive
    -- reference versions. Activation validates targets against the new topology.
);

create table ingestion.bus_arrival_observation (
    id                   uuid          not null,
    raw_observation_id   uuid          not null,
    poll_run_id          uuid          not null,
    reference_version_id uuid          not null,
    stop_code            varchar(12)   not null,
    service_id           varchar(20)   not null,
    operator             varchar(40),
    direction_id         smallint,
    visit_order          smallint      not null,
    estimated_arrival_at timestamptz,
    latitude             numeric(10,7),
    longitude            numeric(10,7),
    load_category        varchar(20),
    feature              varchar(40),
    observed_at          timestamptz,
    fetched_at           timestamptz   not null,
    ingested_at          timestamptz   not null,
    record_checksum      char(64)      not null,

    constraint pk_bus_arrival_observation primary key (id),
    constraint ck_bus_arrival_observation_visit_order
        check (visit_order between 1 and 3),
    constraint fk_bus_arrival_observation_raw_observation
        foreign key (raw_observation_id) references ingestion.raw_observation (id),
    constraint fk_bus_arrival_observation_poll_run
        foreign key (poll_run_id) references ingestion.poll_run (id),
    constraint fk_bus_arrival_observation_reference_version
        foreign key (reference_version_id) references ingestion.reference_dataset_version (id),
    constraint fk_bus_arrival_observation_bus_stop
        foreign key (reference_version_id, stop_code)
        references ingestion.bus_stop (reference_version_id, stop_code),
    constraint uq_bus_arrival_observation_raw_service_visit_checksum
        unique (raw_observation_id, service_id, visit_order, record_checksum)
);

create index ix_bus_arrival_stop_time
    on ingestion.bus_arrival_observation (stop_code, fetched_at desc);

create index ix_bus_arrival_service_time
    on ingestion.bus_arrival_observation (service_id, direction_id, fetched_at desc);
