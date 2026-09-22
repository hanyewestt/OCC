-- Plan 1 (01-platform-auth.md) "Database schema", table 1 of 2.
-- Constraint names follow CONVENTIONS.md §9 (pk_/uq_/fk_/ck_/ix_ prefixes);
-- the plan's DDL left them unnamed.
-- Cognito remains authoritative for password, verified email, and federation;
-- none of that is stored here.

create table operations.user_profile (
    cognito_sub        varchar(64)  not null,
    display_name       varchar(100) not null,
    application_role   varchar(20)  not null,
    contact_preference varchar(20)  not null default 'IN_APP',
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0,

    constraint pk_user_profile primary key (cognito_sub),
    constraint ck_user_profile_application_role
        check (application_role in ('ADMIN', 'CONTROLLER', 'DRIVER')),
    constraint ck_user_profile_contact_preference
        check (contact_preference in ('IN_APP', 'NONE'))
);

comment on column operations.user_profile.application_role is
    'Non-user-editable projection of the Cognito group. Must match the validated token group.';
