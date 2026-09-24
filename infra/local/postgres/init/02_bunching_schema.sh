#!/usr/bin/env bash
# Safe to rerun against an existing volume. Never resets data or changes existing passwords.
set -euo pipefail

if [[ -z "${OCC_BUNCHING_DB_PASSWORD:-}" ]]; then
    echo "Bunching schema setup skipped: OCC_BUNCHING_DB_PASSWORD is unset."
    exit 0
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v bunching_pw="$OCC_BUNCHING_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE occ_bunching LOGIN PASSWORD %L', :'bunching_pw')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'occ_bunching')
\gexec
CREATE SCHEMA IF NOT EXISTS bunching AUTHORIZATION occ_bunching;
REVOKE ALL ON SCHEMA bunching FROM PUBLIC;
ALTER ROLE occ_bunching SET search_path = bunching, public;
ALTER ROLE occ_bunching SET timezone = 'UTC';
SQL
