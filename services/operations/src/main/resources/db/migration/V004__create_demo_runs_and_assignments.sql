-- Draft demo fleet is prototype-owned. It never represents verified DataMall vehicles.
create table operations.demo_run (
    id uuid not null,
    service_id varchar(20) not null,
    direction_id smallint not null,
    status varchar(20) not null,
    created_by_sub varchar(64) not null,
    created_at timestamptz not null,
    version bigint not null default 0,
    constraint pk_demo_run primary key (id),
    constraint ck_demo_run_direction check (direction_id > 0),
    constraint ck_demo_run_status check (status in ('DRAFT', 'READY', 'CLOSED'))
);

create table operations.demo_vehicle (
    id uuid not null,
    run_id uuid not null,
    display_label varchar(80) not null,
    synthetic_plate varchar(20),
    latitude numeric(10,7) not null,
    longitude numeric(10,7) not null,
    created_at timestamptz not null,
    classification varchar(20) not null default 'SIMULATION',
    constraint pk_demo_vehicle primary key (id),
    constraint uq_demo_vehicle_run_id unique (run_id, id),
    constraint uq_demo_vehicle_run_label unique (run_id, display_label),
    constraint uq_demo_vehicle_run_plate unique (run_id, synthetic_plate),
    constraint fk_demo_vehicle_run foreign key (run_id) references operations.demo_run (id),
    constraint ck_demo_vehicle_latitude check (latitude between -90 and 90),
    constraint ck_demo_vehicle_longitude check (longitude between -180 and 180),
    constraint ck_demo_vehicle_classification check (classification = 'SIMULATION')
);

create table operations.demo_driver (
    id uuid not null,
    run_id uuid not null,
    display_name varchar(100) not null,
    created_at timestamptz not null,
    constraint pk_demo_driver primary key (id),
    constraint uq_demo_driver_run_id unique (run_id, id),
    constraint fk_demo_driver_run foreign key (run_id) references operations.demo_run (id)
);

create table operations.demo_assignment (
    id uuid not null,
    run_id uuid not null,
    vehicle_id uuid not null,
    driver_id uuid not null,
    created_at timestamptz not null,
    constraint pk_demo_assignment primary key (id),
    constraint uq_demo_assignment_vehicle unique (vehicle_id),
    constraint uq_demo_assignment_driver unique (driver_id),
    constraint fk_demo_assignment_run foreign key (run_id) references operations.demo_run (id),
    constraint fk_demo_assignment_vehicle foreign key (run_id, vehicle_id)
        references operations.demo_vehicle (run_id, id),
    constraint fk_demo_assignment_driver foreign key (run_id, driver_id)
        references operations.demo_driver (run_id, id)
);

create index ix_demo_vehicle_run on operations.demo_vehicle (run_id, created_at);
create index ix_demo_assignment_run on operations.demo_assignment (run_id, created_at);
