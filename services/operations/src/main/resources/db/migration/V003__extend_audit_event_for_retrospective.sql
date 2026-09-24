-- Retrospective logging, part 1 of 3.
-- CONVENTIONS.md §14: audit records capture actor, action, target type and ID,
-- time, OUTCOME, REASON, and request ID. Plan 1's audit_event has no typed
-- outcome/reason and cannot distinguish a system actor from a controller.
-- Forward-only change: columns are added, nothing existing is altered.

alter table operations.audit_event
    add column actor_type varchar(20) not null default 'SYSTEM',
    add column outcome    varchar(20) not null default 'SUCCESS',
    add column reason     varchar(500);

-- Backfill rows written before this migration (e.g. Plan 1 role changes carry an
-- actor_sub but no actor_type). Take the role from the profile projection, which
-- lives in the same schema, so no cross-schema read is involved.
update operations.audit_event ae
   set actor_type = up.application_role
  from operations.user_profile up
 where ae.actor_sub = up.cognito_sub;

alter table operations.audit_event
    add constraint ck_audit_event_actor_type
        check (actor_type in ('SYSTEM', 'ADMIN', 'CONTROLLER', 'DRIVER')),
    add constraint ck_audit_event_outcome
        check (outcome in ('SUCCESS', 'REJECTED', 'FAILED')),
    -- One direction only: a human actor must be identifiable. The reverse
    -- (SYSTEM => no actor_sub) is NOT enforced, because Plan 1 code that predates
    -- this migration omits actor_type and would otherwise be rejected.
    add constraint ck_audit_event_human_actor_has_sub
        check (actor_type = 'SYSTEM' or actor_sub is not null);

comment on column operations.audit_event.actor_sub is
    'Cognito subject of the human actor. Required when actor_type is ADMIN/CONTROLLER/DRIVER. New code must set actor_type explicitly; the SYSTEM default exists only for compatibility.';
comment on column operations.audit_event.reason is
    'Free-text justification: controller override reason, rejection cause, or system decision basis.';

-- Retrospective queries: "everything in this window", "everything in this request",
-- "everything this person did". The existing (entity_type, entity_id, occurred_at) index
-- already covers "the timeline of one incident/directive".
create index ix_audit_event_occurred_at on operations.audit_event (occurred_at desc);
create index ix_audit_event_request_id  on operations.audit_event (request_id);
create index ix_audit_event_actor_time  on operations.audit_event (actor_sub, occurred_at desc)
    where actor_sub is not null;

-- ---------------------------------------------------------------------------
-- Append-only enforcement. CONVENTIONS.md §9: records referenced by audit are
-- never destructively updated or deleted. The service role owns the table, so a
-- REVOKE would be cosmetic (an owner can always re-grant); a trigger makes the
-- rule fail loudly in every code path, including psql.
-- ---------------------------------------------------------------------------
create or replace function operations.reject_mutation() returns trigger
language plpgsql as $$
begin
    raise exception 'operations.% is append-only (% not permitted)', tg_table_name, tg_op
        using errcode = 'insufficient_privilege';
end;
$$;

create trigger trg_audit_event_append_only
    before update or delete on operations.audit_event
    for each row execute function operations.reject_mutation();
