#!/usr/bin/env bash
# Starts the shared local database plus the ingestion and bunching services.
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

if [[ ! -f .env ]]; then
    echo "Missing .env. Create it first with: cp .env.example .env" >&2
    exit 1
fi

set -a
source .env
set +a

: "${OCC_BUNCHING_DB_URL:?set OCC_BUNCHING_DB_URL in .env}"
: "${OCC_BUNCHING_DB_PASSWORD:?set OCC_BUNCHING_DB_PASSWORD in .env}"
: "${OCC_BUNCHING_STALE_AFTER:?set OCC_BUNCHING_STALE_AFTER in .env}"

docker compose --env-file .env -f infra/local/compose.yaml up -d postgres

for _ in {1..30}; do
    if [[ "$(docker inspect --format '{{.State.Health.Status}}' occ-postgres)" == "healthy" ]]; then
        break
    fi
    sleep 2
done

if [[ "$(docker inspect --format '{{.State.Health.Status}}' occ-postgres)" != "healthy" ]]; then
    echo "PostgreSQL did not become healthy." >&2
    exit 1
fi

bash services/ingestion/mvnw -f services/ingestion/pom.xml spring-boot:run \
    -Dspring-boot.run.profiles=local &
ingestion_pid=$!

cleanup() {
    kill "$ingestion_pid" "${bunching_pid:-}" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# Bunching reads the ingestion V007 views, so wait until ingestion has applied them.
for _ in {1..30}; do
    if docker exec occ-postgres psql -U occ_admin -d occ -tAc \
        "select exists (select 1 from ingestion.flyway_schema_history where version = '007' and success);" \
        2>/dev/null | grep -qx 't'; then
        break
    fi
    sleep 2
done

if ! docker exec occ-postgres psql -U occ_admin -d occ -tAc \
    "select exists (select 1 from ingestion.flyway_schema_history where version = '007' and success);" \
    2>/dev/null | grep -qx 't'; then
    echo "Ingestion did not apply migration V007; stopping both services." >&2
    exit 1
fi

# Grant the bunching role its narrow read-only view contract after V007 creates it.
# The script is idempotent and never grants access to the underlying ingestion tables.
docker compose --env-file .env -f infra/local/compose.yaml exec -T postgres \
    bash /docker-entrypoint-initdb.d/03_bunching_read_grants.sh

bash services/ingestion/mvnw -f services/bus-bunching-detection/pom.xml spring-boot:run &
bunching_pid=$!

# macOS ships Bash 3.2, which lacks `wait -n`. Poll both child processes so
# either service exiting stops the other one too.
while kill -0 "$ingestion_pid" 2>/dev/null && kill -0 "$bunching_pid" 2>/dev/null; do
    sleep 1
done

wait "$ingestion_pid" 2>/dev/null || true
wait "$bunching_pid" 2>/dev/null || true
exit 1
