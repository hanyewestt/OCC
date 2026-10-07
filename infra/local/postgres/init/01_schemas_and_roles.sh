#!/usr/bin/env bash
# infra/local/postgres/init/01_schemas_and_roles.sh
#
# Runs once on first container start (docker-entrypoint-initdb.d).
# Creates the PostGIS extension and the ingestion schema + user.
# described in docs/plans/week-01/README.md "Database ownership":
#
#   schema      owner/user      may write
#   ---------   -------------   ---------------------
#   ingestion   occ_ingestion   ingestion only
#
# Tables are NOT created here. Each service owns its tables through Flyway.
# This script only does what Flyway cannot: create the role Flyway logs in as,
# and the schema that role owns.
#
# Passwords come from the environment (compose.yaml -> .env) and are passed
# to psql as variables so they are never interpolated into a SQL string.
set -euo pipefail

: "${OCC_INGESTION_DB_PASSWORD:?missing}"

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v ingestion_pw="$OCC_INGESTION_DB_PASSWORD" \
     <<'SQL'

-- PostGIS lives in the shared `public` schema so every service can use the
-- geometry type and spatial_ref_sys without a copy per schema.
CREATE EXTENSION IF NOT EXISTS postgis;

-- ---------------------------------------------------------------------------
-- Roles. LOGIN only; no CREATEDB, no SUPERUSER, no cross-schema grants.
-- ---------------------------------------------------------------------------
CREATE ROLE occ_ingestion  LOGIN PASSWORD :'ingestion_pw';

-- ---------------------------------------------------------------------------
-- Schemas, each owned by exactly one service role.
-- Owning the schema is what lets Flyway create flyway_schema_history and
-- the service's tables inside it. A non-owner gets nothing by default.
-- ---------------------------------------------------------------------------
CREATE SCHEMA ingestion  AUTHORIZATION occ_ingestion;

-- Belt and braces: make sure PUBLIC has no privileges on service schemas
-- (PostgreSQL grants none by default, but be explicit so a future change
-- to a default can't silently open them up).
REVOKE ALL ON SCHEMA ingestion  FROM PUBLIC;

-- Services must not create objects in `public` (keeps PostGIS-only there).
-- PG15+ already revokes CREATE on public from PUBLIC; being explicit costs nothing.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Each role's default search_path: its own schema first, then public for
-- PostGIS types/functions. Unqualified names in migrations resolve correctly.
ALTER ROLE occ_ingestion  SET search_path = ingestion,  public;

-- Timestamps are stored as UTC timestamptz per conventions.
ALTER ROLE occ_ingestion  SET timezone = 'UTC';

SQL

echo "OCC schemas and roles created."
