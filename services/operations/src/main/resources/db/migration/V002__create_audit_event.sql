-- Plan 1 (01-platform-auth.md) "Database schema", table 2 of 2.

create table operations.audit_event (
    id          uuid         not null,
    actor_sub   varchar(64),
    action      varchar(80)  not null,
    entity_type varchar(80)  not null,
    entity_id   varchar(160) not null,
    request_id  varchar(80)  not null,
    occurred_at timestamptz  not null,
    details     jsonb        not null default '{}'::jsonb,

    constraint pk_audit_event primary key (id)
);

create index ix_audit_event_entity_time
    on operations.audit_event (entity_type, entity_id, occurred_at desc);
