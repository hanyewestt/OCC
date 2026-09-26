#!/usr/bin/env bash
# Rerun after ingestion Flyway creates V007 views; no ingestion table writes are granted.
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'occ_bunching')
       AND to_regclass('ingestion.bunching_snapshot') IS NOT NULL
       AND to_regclass('ingestion.bunching_route_direction') IS NOT NULL THEN
        GRANT USAGE ON SCHEMA ingestion TO occ_bunching;
        GRANT SELECT ON ingestion.bunching_snapshot, ingestion.bunching_route_direction TO occ_bunching;
    ELSE
        RAISE NOTICE 'Run this script again after provisioning occ_bunching and ingestion migration V007.';
    END IF;
END $$;
SQL
