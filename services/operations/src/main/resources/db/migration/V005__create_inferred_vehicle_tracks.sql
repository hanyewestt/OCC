-- Rebuildable identity hypotheses. No foreign keys to transient ingestion telemetry.
create table operations.inferred_vehicle_track (
    id uuid primary key,
    service_id varchar(20) not null,
    direction_id integer not null,
    operator varchar(40) not null,
    route_fingerprint varchar(36) not null,
    latitude double precision not null,
    longitude double precision not null,
    progress_meters double precision not null,
    anchor_stop varchar(10) not null,
    anchor_eta timestamptz,
    first_seen_at timestamptz not null,
    last_seen_at timestamptz not null,
    status varchar(20) not null check (status in ('PROVISIONAL','ACTIVE','AMBIGUOUS','EXPIRED')),
    confidence double precision not null check (confidence between 0 and 1),
    confirmations integer not null,
    version bigint not null default 0
);
create index ix_inferred_track_service on operations.inferred_vehicle_track(service_id, last_seen_at);
create table operations.inferred_observation_link (
    observation_id uuid primary key,
    track_id uuid references operations.inferred_vehicle_track(id) on delete cascade,
    fetched_at timestamptz not null,
    outcome varchar(30) not null
);
create index ix_inferred_link_track on operations.inferred_observation_link(track_id);
create index ix_inferred_link_time on operations.inferred_observation_link(fetched_at);
