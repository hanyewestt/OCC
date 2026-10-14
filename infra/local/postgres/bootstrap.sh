#!/usr/bin/env bash
# Idempotently provision the local service roles/schemas and migration read access.
set -euo pipefail

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
: "${OCC_INGESTION_DB_PASSWORD:?OCC_INGESTION_DB_PASSWORD is required}"
: "${OCC_DISRUPTION_DB_PASSWORD:?OCC_DISRUPTION_DB_PASSWORD is required}"
: "${OCC_PLANNING_DB_PASSWORD:?OCC_PLANNING_DB_PASSWORD is required}"

export PGPASSWORD="${POSTGRES_PASSWORD}"
psql_args=()
if [[ -n "${PGHOST:-}" ]]; then
    psql_args+=(--host "${PGHOST}")
fi
psql "${psql_args[@]}" --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
    --set ON_ERROR_STOP=1 \
    --set ingestion_password="${OCC_INGESTION_DB_PASSWORD}" \
    --set disruption_password="${OCC_DISRUPTION_DB_PASSWORD}" \
    --set planning_password="${OCC_PLANNING_DB_PASSWORD}" <<'SQL'
CREATE EXTENSION IF NOT EXISTS postgis;

SELECT format('CREATE ROLE occ_ingestion LOGIN PASSWORD %L', :'ingestion_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'occ_ingestion')
\gexec
SELECT format('CREATE ROLE occ_disruption LOGIN PASSWORD %L', :'disruption_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'occ_disruption')
\gexec
SELECT format('CREATE ROLE occ_planning LOGIN PASSWORD %L', :'planning_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'occ_planning')
\gexec

ALTER ROLE occ_ingestion LOGIN PASSWORD :'ingestion_password';
ALTER ROLE occ_ingestion SET search_path TO ingestion, public;
ALTER ROLE occ_ingestion SET timezone TO 'UTC';
ALTER ROLE occ_disruption LOGIN PASSWORD :'disruption_password';
ALTER ROLE occ_disruption SET search_path TO disruption, public;
ALTER ROLE occ_disruption SET timezone TO 'UTC';
ALTER ROLE occ_planning LOGIN PASSWORD :'planning_password';
ALTER ROLE occ_planning SET search_path TO planning, public;
ALTER ROLE occ_planning SET timezone TO 'UTC';

CREATE SCHEMA IF NOT EXISTS ingestion AUTHORIZATION occ_ingestion;
CREATE SCHEMA IF NOT EXISTS disruption AUTHORIZATION occ_disruption;
CREATE SCHEMA IF NOT EXISTS planning AUTHORIZATION occ_planning;
ALTER SCHEMA ingestion OWNER TO occ_ingestion;
ALTER SCHEMA disruption OWNER TO occ_disruption;
ALTER SCHEMA planning OWNER TO occ_planning;
REVOKE ALL ON SCHEMA ingestion, disruption, planning FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Target Flyway migrations read these source schemas once. Runtime services continue
-- to use their owning schemas and call one another only through private HTTP APIs.
SELECT 'GRANT USAGE ON SCHEMA bunching TO occ_disruption'
 WHERE EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'bunching')
\gexec
SELECT 'GRANT SELECT ON ALL TABLES IN SCHEMA bunching TO occ_disruption'
 WHERE EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'bunching')
\gexec
SELECT 'GRANT USAGE ON SCHEMA diagnosis TO occ_planning'
 WHERE EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'diagnosis')
\gexec
SELECT 'GRANT SELECT ON ALL TABLES IN SCHEMA diagnosis TO occ_planning'
 WHERE EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'diagnosis')
\gexec
SELECT 'GRANT USAGE ON SCHEMA recommendation TO occ_planning'
 WHERE EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'recommendation')
\gexec
SELECT 'GRANT SELECT ON ALL TABLES IN SCHEMA recommendation TO occ_planning'
 WHERE EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'recommendation')
\gexec
SQL

printf '%s\n' 'Local OCC service schemas and migration read grants are ready.'
