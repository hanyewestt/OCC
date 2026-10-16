-- Plan 3 "Database schema" — feed health.
-- NOTE: no `stale` column by design. Staleness is computed from server time
-- and last_success_at (CONVENTIONS.md §10); a persisted boolean would drift.

create table ingestion.feed_status (
    source               varchar(40)  not null,
    scope_key            varchar(120) not null,
    status               varchar(20)  not null,
    last_attempt_at      timestamptz,
    last_success_at      timestamptz,
    consecutive_failures integer      not null default 0,
    stale_after_seconds  integer      not null,
    message              varchar(500),
    updated_at           timestamptz  not null,

    constraint pk_feed_status primary key (source, scope_key),
    constraint ck_feed_status_status
        check (status in ('HEALTHY', 'DEGRADED', 'FAILED', 'NEVER_RUN'))
);
