#!/usr/bin/env bash
# Safe to rerun against an existing volume. Does not reset diagnosis data.
set -euo pipefail

if [[ -z "${OCC_DIAGNOSIS_DB_PASSWORD:-}" ]]; then
    echo "Root-cause diagnosis schema setup skipped: OCC_DIAGNOSIS_DB_PASSWORD is unset."
    exit 0
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v diagnosis_pw="$OCC_DIAGNOSIS_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE occ_diagnosis LOGIN PASSWORD %L', :'diagnosis_pw')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'occ_diagnosis')
\gexec
CREATE SCHEMA IF NOT EXISTS diagnosis AUTHORIZATION occ_diagnosis;
REVOKE ALL ON SCHEMA diagnosis FROM PUBLIC;
ALTER ROLE occ_diagnosis SET search_path = diagnosis, public;
ALTER ROLE occ_diagnosis SET timezone = 'UTC';
SQL
