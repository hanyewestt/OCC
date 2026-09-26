alter table operations.inferred_observation_link add column stop_code varchar(10);
alter table operations.inferred_observation_link add column estimated_arrival_at timestamptz;
create table operations.inferred_track_frame (
    service_id varchar(20) primary key,
    source_at timestamptz not null
);
create table operations.inferred_track_frame_member (
    service_id varchar(20) not null references operations.inferred_track_frame(service_id) on delete cascade,
    track_id uuid not null,
    primary key (service_id, track_id)
);
