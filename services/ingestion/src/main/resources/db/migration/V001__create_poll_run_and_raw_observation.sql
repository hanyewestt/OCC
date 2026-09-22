-- Plan 3 (03-bus-data-polling.md) "Database schema" — provenance layer.
-- Constraint names follow CONVENTIONS.md §9; the plan's DDL left them unnamed.

create table ingestion.poll_run (
    id               uuid         not null,
    source           varchar(40)  not null,
    scope_key        varchar(120) not null,
    idempotency_key  varchar(120) not null,
    started_at       timestamptz  not null,
    completed_at     timestamptz,
    outcome          varchar(20)  not null,
    http_status      integer,
    records_received integer      not null default 0,
    error_code       varchar(80),
    error_message    varchar(500),

    constraint pk_poll_run primary key (id),
    constraint uq_poll_run_idempotency_key unique (idempotency_key),
    constraint ck_poll_run_outcome
        check (outcome in ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED'))
);

create table ingestion.raw_observation (
    id                 uuid         not null,
    poll_run_id        uuid         not null,
    source             varchar(40)  not null,
    source_key         varchar(200) not null,
    request_parameters jsonb        not null,
    payload            jsonb        not null,
    payload_checksum   char(64)     not null,
    observed_at        timestamptz,
    fetched_at         timestamptz  not null,
    ingested_at        timestamptz  not null,
    http_status        integer      not null,
    classification     varchar(30)  not null default 'OBSERVATION_ONLY',

    constraint pk_raw_observation primary key (id),
    constraint fk_raw_observation_poll_run
        foreign key (poll_run_id) references ingestion.poll_run (id),
    constraint uq_raw_observation_run_key_checksum
        unique (poll_run_id, source_key, payload_checksum)
);
