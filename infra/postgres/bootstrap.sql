-- Provision service runtime accounts and isolated migration ownership.
-- This file is invoked by psql (not a shell) after PostgreSQL reports healthy.
\set ON_ERROR_STOP on

\getenv ingestion_runtime_role OCC_INGESTION_DB_USERNAME
\getenv ingestion_runtime_password OCC_INGESTION_DB_PASSWORD
\getenv ingestion_migrator_role OCC_INGESTION_DB_MIGRATOR_USERNAME
\getenv ingestion_migrator_password OCC_INGESTION_DB_MIGRATOR_PASSWORD
\getenv disruption_runtime_role OCC_DISRUPTION_DB_USERNAME
\getenv disruption_runtime_password OCC_DISRUPTION_DB_PASSWORD
\getenv disruption_migrator_role OCC_DISRUPTION_DB_MIGRATOR_USERNAME
\getenv disruption_migrator_password OCC_DISRUPTION_DB_MIGRATOR_PASSWORD
\getenv planning_runtime_role OCC_PLANNING_DB_USERNAME
\getenv planning_runtime_password OCC_PLANNING_DB_PASSWORD
\getenv planning_migrator_role OCC_PLANNING_DB_MIGRATOR_USERNAME
\getenv planning_migrator_password OCC_PLANNING_DB_MIGRATOR_PASSWORD

CREATE EXTENSION IF NOT EXISTS postgis;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

BEGIN;

CREATE TEMP TABLE occ_bootstrap_service (
    schema_name text PRIMARY KEY,
    runtime_role text NOT NULL UNIQUE,
    runtime_password text NOT NULL,
    migrator_role text NOT NULL UNIQUE,
    migrator_password text NOT NULL
) ON COMMIT PRESERVE ROWS;

INSERT INTO occ_bootstrap_service VALUES
    ('ingestion', :'ingestion_runtime_role', :'ingestion_runtime_password',
                 :'ingestion_migrator_role', :'ingestion_migrator_password'),
    ('disruption', :'disruption_runtime_role', :'disruption_runtime_password',
                  :'disruption_migrator_role', :'disruption_migrator_password'),
    ('planning', :'planning_runtime_role', :'planning_runtime_password',
                :'planning_migrator_role', :'planning_migrator_password');

DO $bootstrap$
DECLARE
    service record;
    owner_role text;
    runtime_oid oid;
    migrator_oid oid;
    owner_oid oid;
    schema_oid oid;
    current_owner text;
    object_record record;
    owner_conflict text;
BEGIN
    IF EXISTS (
        SELECT 1 FROM occ_bootstrap_service
         WHERE runtime_role !~ '^[a-z][a-z0-9_]{0,62}$'
            OR migrator_role !~ '^[a-z][a-z0-9_]{0,62}$'
            OR runtime_role = migrator_role
            OR runtime_password = '' OR migrator_password = '') THEN
        RAISE EXCEPTION 'Service database role names or passwords are invalid';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM occ_bootstrap_service first_role
          JOIN occ_bootstrap_service second_role
            ON first_role.schema_name < second_role.schema_name
           AND (first_role.runtime_role IN (second_role.runtime_role, second_role.migrator_role)
             OR first_role.migrator_role IN (second_role.runtime_role, second_role.migrator_role))) THEN
        RAISE EXCEPTION 'A database role is configured for more than one service';
    END IF;

    FOR service IN SELECT * FROM occ_bootstrap_service ORDER BY schema_name LOOP
        owner_role := 'occ_' || service.schema_name || '_owner';

        IF service.runtime_role IN (owner_role, current_user, 'postgres')
           OR service.migrator_role IN (owner_role, service.runtime_role, current_user, 'postgres') THEN
            RAISE EXCEPTION 'Service roles must be distinct from database administration and schema-owner roles';
        END IF;

        SELECT oid INTO owner_oid FROM pg_roles WHERE rolname = owner_role;
        IF owner_oid IS NULL THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', owner_role);
            SELECT oid INTO owner_oid FROM pg_roles WHERE rolname = owner_role;
        ELSIF EXISTS (SELECT 1 FROM pg_roles WHERE oid = owner_oid
                       AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) THEN
            RAISE EXCEPTION 'Existing schema-owner role % has unsafe attributes; review it manually', owner_role;
        END IF;

        SELECT oid INTO runtime_oid FROM pg_roles WHERE rolname = service.runtime_role;
        IF runtime_oid IS NULL THEN
            EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
                           service.runtime_role, service.runtime_password);
            SELECT oid INTO runtime_oid FROM pg_roles WHERE rolname = service.runtime_role;
        ELSIF EXISTS (SELECT 1 FROM pg_roles WHERE oid = runtime_oid
                       AND (NOT rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) THEN
            RAISE EXCEPTION 'Existing runtime role % has unsafe attributes; review it manually', service.runtime_role;
        END IF;

        IF EXISTS (SELECT 1 FROM pg_auth_members WHERE member = runtime_oid) THEN
            RAISE EXCEPTION 'Runtime role % has role memberships; remove them before bootstrap', service.runtime_role;
        END IF;

        SELECT oid INTO migrator_oid FROM pg_roles WHERE rolname = service.migrator_role;
        IF migrator_oid IS NULL THEN
            EXECUTE format('CREATE ROLE %I LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
                           service.migrator_role, service.migrator_password);
            SELECT oid INTO migrator_oid FROM pg_roles WHERE rolname = service.migrator_role;
        ELSIF EXISTS (SELECT 1 FROM pg_roles WHERE oid = migrator_oid
                       AND (NOT rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) THEN
            RAISE EXCEPTION 'Existing migrator role % has unsafe attributes; review it manually', service.migrator_role;
        END IF;

        IF EXISTS (
            SELECT 1 FROM pg_auth_members
             WHERE member = migrator_oid AND roleid <> owner_oid) THEN
            RAISE EXCEPTION 'Migrator role % has an unexpected role membership', service.migrator_role;
        END IF;

        EXECUTE format('ALTER ROLE %I NOINHERIT', service.migrator_role);
        EXECUTE format('GRANT %I TO %I WITH INHERIT FALSE, SET TRUE', owner_role, service.migrator_role);
        EXECUTE format('ALTER ROLE %I SET search_path TO %I, public', service.runtime_role, service.schema_name);
        EXECUTE format('ALTER ROLE %I SET timezone TO %L', service.runtime_role, 'UTC');
        EXECUTE format('ALTER ROLE %I SET search_path TO %I, public', service.migrator_role, service.schema_name);
        EXECUTE format('ALTER ROLE %I SET timezone TO %L', service.migrator_role, 'UTC');

        SELECT oid, pg_get_userbyid(nspowner) INTO schema_oid, current_owner
          FROM pg_namespace WHERE nspname = service.schema_name;
        IF schema_oid IS NULL THEN
            EXECUTE format('CREATE SCHEMA %I AUTHORIZATION %I', service.schema_name, owner_role);
            SELECT oid, pg_get_userbyid(nspowner) INTO schema_oid, current_owner
              FROM pg_namespace WHERE nspname = service.schema_name;
        ELSIF current_owner NOT IN (service.runtime_role, owner_role) THEN
            RAISE EXCEPTION 'Schema % is owned by unexpected role %; refusing ownership transfer', service.schema_name, current_owner;
        END IF;

        SELECT format('relation %I.%I is owned by %I', service.schema_name, relation.relname,
                      pg_get_userbyid(relation.relowner))
          INTO owner_conflict
          FROM pg_class relation
         WHERE relation.relnamespace = schema_oid
           AND relation.relkind IN ('r', 'p', 'v', 'm', 'S', 'f', 'c')
           AND relation.relowner NOT IN (runtime_oid, owner_oid)
         LIMIT 1;
        IF owner_conflict IS NOT NULL THEN
            RAISE EXCEPTION 'Schema % contains unexpected ownership: %', service.schema_name, owner_conflict;
        END IF;
        SELECT format('routine %I.%I is owned by %I', service.schema_name, routine.proname,
                      pg_get_userbyid(routine.proowner))
          INTO owner_conflict
          FROM pg_proc routine
         WHERE routine.pronamespace = schema_oid AND routine.proowner NOT IN (runtime_oid, owner_oid)
         LIMIT 1;
        IF owner_conflict IS NOT NULL THEN
            RAISE EXCEPTION 'Schema % contains unexpected ownership: %', service.schema_name, owner_conflict;
        END IF;
        SELECT format('type %I.%I is owned by %I', service.schema_name, type_object.typname,
                      pg_get_userbyid(type_object.typowner))
          INTO owner_conflict
          FROM pg_type type_object
         WHERE type_object.typnamespace = schema_oid AND type_object.typowner NOT IN (runtime_oid, owner_oid)
         LIMIT 1;
        IF owner_conflict IS NOT NULL THEN
            RAISE EXCEPTION 'Schema % contains unexpected ownership: %', service.schema_name, owner_conflict;
        END IF;

        IF EXISTS (
            SELECT 1 FROM pg_namespace namespace
             WHERE namespace.nspowner = runtime_oid AND namespace.nspname <> service.schema_name
               AND namespace.nspname NOT LIKE 'pg_temp_%' AND namespace.nspname NOT LIKE 'pg_toast_temp_%')
           OR EXISTS (
            SELECT 1 FROM pg_class relation
              JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
             WHERE relation.relowner = runtime_oid AND namespace.nspname <> service.schema_name
               AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
               AND namespace.nspname NOT LIKE 'pg_toast%'
               AND namespace.nspname NOT LIKE 'pg_temp_%')
           OR EXISTS (
            SELECT 1 FROM pg_proc routine
              JOIN pg_namespace namespace ON namespace.oid = routine.pronamespace
             WHERE routine.proowner = runtime_oid AND namespace.nspname <> service.schema_name
               AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
               AND namespace.nspname NOT LIKE 'pg_temp_%')
           OR EXISTS (
            SELECT 1 FROM pg_type type_object
              JOIN pg_namespace namespace ON namespace.oid = type_object.typnamespace
             WHERE type_object.typowner = runtime_oid AND namespace.nspname <> service.schema_name
               AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
               AND namespace.nspname NOT LIKE 'pg_toast%'
               AND namespace.nspname NOT LIKE 'pg_temp_%')
           OR EXISTS (SELECT 1 FROM pg_database WHERE datdba = runtime_oid)
           OR EXISTS (SELECT 1 FROM pg_tablespace WHERE spcowner = runtime_oid)
           OR EXISTS (SELECT 1 FROM pg_largeobject_metadata WHERE lomowner = runtime_oid) THEN
            RAISE EXCEPTION 'Runtime role % owns objects outside schema %; refusing bootstrap', service.runtime_role, service.schema_name;
        END IF;

        EXECUTE format('ALTER SCHEMA %I OWNER TO %I', service.schema_name, owner_role);

        FOR object_record IN
            SELECT relation.relkind, relation.relname
              FROM pg_class relation
             WHERE relation.relnamespace = schema_oid AND relation.relowner = runtime_oid
               AND relation.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
        LOOP
            CASE object_record.relkind
                WHEN 'S' THEN EXECUTE format('ALTER SEQUENCE %I.%I OWNER TO %I', service.schema_name, object_record.relname, owner_role);
                WHEN 'v' THEN EXECUTE format('ALTER VIEW %I.%I OWNER TO %I', service.schema_name, object_record.relname, owner_role);
                WHEN 'm' THEN EXECUTE format('ALTER MATERIALIZED VIEW %I.%I OWNER TO %I', service.schema_name, object_record.relname, owner_role);
                WHEN 'f' THEN EXECUTE format('ALTER FOREIGN TABLE %I.%I OWNER TO %I', service.schema_name, object_record.relname, owner_role);
                ELSE EXECUTE format('ALTER TABLE %I.%I OWNER TO %I', service.schema_name, object_record.relname, owner_role);
            END CASE;
        END LOOP;

        FOR object_record IN
            SELECT routine.prokind, routine.proname,
                   pg_get_function_identity_arguments(routine.oid) AS identity_arguments
              FROM pg_proc routine
             WHERE routine.pronamespace = schema_oid AND routine.proowner = runtime_oid
        LOOP
            EXECUTE format('ALTER %s %I.%I(%s) OWNER TO %I',
                CASE object_record.prokind WHEN 'p' THEN 'PROCEDURE' WHEN 'a' THEN 'AGGREGATE' ELSE 'FUNCTION' END,
                service.schema_name, object_record.proname, object_record.identity_arguments, owner_role);
        END LOOP;

        FOR object_record IN
            SELECT type_object.typname
              FROM pg_type type_object
             WHERE type_object.typnamespace = schema_oid AND type_object.typowner = runtime_oid
               AND type_object.typrelid = 0 AND type_object.typelem = 0 AND type_object.typisdefined
        LOOP
            EXECUTE format('ALTER TYPE %I.%I OWNER TO %I', service.schema_name, object_record.typname, owner_role);
        END LOOP;

        EXECUTE format('REVOKE ALL ON SCHEMA %I FROM %I', service.schema_name, service.runtime_role);
        EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC', service.schema_name);
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', service.schema_name, service.runtime_role);
        EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM %I', service.schema_name, service.runtime_role);
        EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM PUBLIC', service.schema_name);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I', service.schema_name, service.runtime_role);
        EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM %I', service.schema_name, service.runtime_role);
        EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM PUBLIC', service.schema_name);
        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', service.schema_name, service.runtime_role);
        EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA %I FROM %I, PUBLIC', service.schema_name, service.runtime_role);

        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON TABLES FROM PUBLIC', owner_role, service.schema_name);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON SEQUENCES FROM PUBLIC', owner_role, service.schema_name);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC', owner_role, service.schema_name);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', owner_role, service.schema_name, service.runtime_role);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO %I', owner_role, service.schema_name, service.runtime_role);
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), service.runtime_role);
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), service.migrator_role);
    END LOOP;
END
$bootstrap$;

-- Legacy-source read rights belong only to the target schema owner used by Flyway.
DO $legacy_import_grants$
DECLARE
    legacy_schema record;
    service record;
BEGIN
    FOR legacy_schema IN
        SELECT * FROM (VALUES
            ('bunching', 'occ_disruption_owner'),
            ('diagnosis', 'occ_planning_owner'),
            ('recommendation', 'occ_planning_owner')
        ) AS legacy(schema_name, import_owner)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = legacy_schema.schema_name) THEN
            EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC', legacy_schema.schema_name);
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM PUBLIC', legacy_schema.schema_name);
            EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM PUBLIC', legacy_schema.schema_name);
            FOR service IN SELECT runtime_role FROM occ_bootstrap_service LOOP
                EXECUTE format('REVOKE ALL ON SCHEMA %I FROM %I', legacy_schema.schema_name, service.runtime_role);
                EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM %I',
                               legacy_schema.schema_name, service.runtime_role);
                EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM %I',
                               legacy_schema.schema_name, service.runtime_role);
            END LOOP;
            EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I',
                           legacy_schema.schema_name, legacy_schema.import_owner);
            EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA %I TO %I',
                           legacy_schema.schema_name, legacy_schema.import_owner);
        END IF;
    END LOOP;
END
$legacy_import_grants$;

COMMIT;
