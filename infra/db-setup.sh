#!/bin/sh
set -eu

# Bootstrap alone needs admin credentials; never pass them to Flyway.
PGPASSWORD="$POSTGRES_PASSWORD" psql --no-psqlrc --host=postgres \
    --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" \
    --set=ON_ERROR_STOP=1 --file=/bootstrap/bootstrap.sql
unset POSTGRES_PASSWORD PGPASSWORD

export FLYWAY_CREATE_SCHEMAS=false FLYWAY_CONNECT_RETRIES=60
export FLYWAY_VALIDATE_MIGRATION_NAMING=true
for service in ingestion disruption planning; do
    prefix=$(printf '%s' "$service" | tr '[:lower:]' '[:upper:]')
    # Read the fixed, internally generated variable names without eval.
    export FLYWAY_USER=$(printenv "OCC_${prefix}_DB_MIGRATOR_USERNAME")
    export FLYWAY_PASSWORD=$(printenv "OCC_${prefix}_DB_MIGRATOR_PASSWORD")
    export "FLYWAY_PLACEHOLDERS_OCC_${prefix}_DB_USERNAME=$(printenv "OCC_${prefix}_DB_USERNAME")"
    export FLYWAY_URL="jdbc:postgresql://postgres:5432/${POSTGRES_DB}?options=-c%20role%3Docc_${service}_owner"
    export FLYWAY_DEFAULT_SCHEMA="$service" FLYWAY_SCHEMAS="$service"
    export FLYWAY_LOCATIONS="filesystem:/migrations/$service"
    export FLYWAY_CALLBACK_LOCATIONS="filesystem:/callbacks/$service"
    flyway migrate
    unset "FLYWAY_PLACEHOLDERS_OCC_${prefix}_DB_USERNAME"
done
