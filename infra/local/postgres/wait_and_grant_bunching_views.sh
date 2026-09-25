#!/usr/bin/env bash
set -euo pipefail

: "${OCC_COMPOSE_STARTUP_TIMEOUT_SECONDS:=180}"

deadline=$((SECONDS + OCC_COMPOSE_STARTUP_TIMEOUT_SECONDS))
until [[ "$(psql -At --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -c "select to_regclass('ingestion.bunching_snapshot') is not null and to_regclass('ingestion.bunching_route_direction') is not null")" == "t" ]]; do
    if (( SECONDS >= deadline )); then
        echo "Timed out waiting for ingestion Flyway views." >&2
        exit 1
    fi
    sleep 2
done

exec bash /docker-entrypoint-initdb.d/03_bunching_read_grants.sh
