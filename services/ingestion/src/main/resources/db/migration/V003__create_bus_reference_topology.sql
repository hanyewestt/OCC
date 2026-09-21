-- Plan 3 "Database schema" — versioned static reference data
-- (bus stops, services, ordered route stops) imported from DataMall.
-- A new import lands as CANDIDATE, is validated, then becomes ACTIVE;
-- the partial unique index guarantees at most one ACTIVE version per dataset.

create table ingestion.reference_dataset_version (
    id           uuid        not null,
    dataset      varchar(40) not null,
    version_key  varchar(80) not null,
    checksum     char(64)    not null,
    status       varchar(20) not null,
    imported_at  timestamptz not null,
    activated_at timestamptz,

    constraint pk_reference_dataset_version primary key (id),
    constraint uq_reference_dataset_version_dataset_key unique (dataset, version_key),
    constraint ck_reference_dataset_version_status
        check (status in ('CANDIDATE', 'ACTIVE', 'SUPERSEDED', 'REJECTED'))
);

create unique index uq_reference_dataset_version_active
    on ingestion.reference_dataset_version (dataset)
    where status = 'ACTIVE';

create table ingestion.bus_stop (
    reference_version_id uuid                  not null,
    stop_code            varchar(12)           not null,   -- string: preserves leading zeroes
    road_name            varchar(160)          not null,
    description          varchar(200)          not null,
    location             geometry(Point, 4326) not null,
    imported_at          timestamptz           not null,

    constraint pk_bus_stop primary key (reference_version_id, stop_code),
    constraint fk_bus_stop_reference_version
        foreign key (reference_version_id) references ingestion.reference_dataset_version (id)
);

create table ingestion.bus_service (
    reference_version_id uuid        not null,
    service_id           varchar(20) not null,
    operator             varchar(40) not null,
    category             varchar(40),
    imported_at          timestamptz not null,

    constraint pk_bus_service primary key (reference_version_id, service_id, operator),
    constraint fk_bus_service_reference_version
        foreign key (reference_version_id) references ingestion.reference_dataset_version (id)
);

create table ingestion.bus_route_stop (
    reference_version_id uuid         not null,
    service_id           varchar(20)  not null,
    operator             varchar(40)  not null,
    direction_id         smallint     not null,
    stop_sequence        integer      not null,
    stop_code            varchar(12)  not null,
    distance_km          numeric(8,3),
    imported_at          timestamptz  not null,

    constraint pk_bus_route_stop
        primary key (reference_version_id, service_id, operator, direction_id, stop_sequence),
    constraint uq_bus_route_stop_service_direction_stop_sequence
        unique (reference_version_id, service_id, operator, direction_id, stop_code, stop_sequence),
    constraint fk_bus_route_stop_reference_version
        foreign key (reference_version_id) references ingestion.reference_dataset_version (id),
    constraint fk_bus_route_stop_bus_service
        foreign key (reference_version_id, service_id, operator)
        references ingestion.bus_service (reference_version_id, service_id, operator),
    constraint fk_bus_route_stop_bus_stop
        foreign key (reference_version_id, stop_code)
        references ingestion.bus_stop (reference_version_id, stop_code)
);
