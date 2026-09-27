#!/usr/bin/env bash
# Safe to rerun against an existing volume. Never resets data or changes existing passwords.
set -euo pipefail

if [[ -z "${OCC_RECOMMENDATION_DB_PASSWORD:-}" ]]; then
    echo "Recommendation schema setup skipped: OCC_RECOMMENDATION_DB_PASSWORD is unset."
    exit 0
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v recommendation_pw="$OCC_RECOMMENDATION_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE occ_recommendation LOGIN PASSWORD %L', :'recommendation_pw')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'occ_recommendation')
\gexec
CREATE SCHEMA IF NOT EXISTS recommendation AUTHORIZATION occ_recommendation;
REVOKE ALL ON SCHEMA recommendation FROM PUBLIC;
ALTER ROLE occ_recommendation SET search_path = recommendation, public;
ALTER ROLE occ_recommendation SET timezone = 'UTC';
SQL
