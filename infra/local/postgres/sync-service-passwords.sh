#!/usr/bin/env bash
# Explicit local maintenance: align existing local roles with Compose environment.
# Does not recreate schemas, tables, or data. Passwords are never printed.
set -euo pipefail
: "${OCC_INGESTION_DB_PASSWORD:?missing}"
: "${OCC_OPERATIONS_DB_PASSWORD:?missing}"
: "${OCC_PLANNING_DB_PASSWORD:?missing}"
: "${OCC_BUNCHING_DB_PASSWORD:?missing}"
: "${POSTGRES_PASSWORD:?missing}"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v admin_name="$POSTGRES_USER" -v admin_pw="$POSTGRES_PASSWORD" \
  -v ingestion_pw="$OCC_INGESTION_DB_PASSWORD" \
  -v operations_pw="$OCC_OPERATIONS_DB_PASSWORD" \
  -v planning_pw="$OCC_PLANNING_DB_PASSWORD" \
  -v bunching_pw="$OCC_BUNCHING_DB_PASSWORD" <<'SQL'
BEGIN;
ALTER ROLE :"admin_name" PASSWORD :'admin_pw';
ALTER ROLE occ_ingestion PASSWORD :'ingestion_pw';
ALTER ROLE occ_operations PASSWORD :'operations_pw';
ALTER ROLE occ_planning PASSWORD :'planning_pw';
ALTER ROLE occ_bunching PASSWORD :'bunching_pw';
COMMIT;
SQL
