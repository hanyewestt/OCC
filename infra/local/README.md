# Local PostgreSQL

This is the only local Compose setup. It runs PostgreSQL 18 with PostGIS 3.6 and one `occ` database. Ingestion, operations, planning, and bunching each have an isolated schema and application account.

## What this setup creates

| Schema | Login role | Owner / purpose |
| --- | --- | --- |
| `ingestion` | `occ_ingestion` | DataMall polling, normalized arrivals, and read-only bunching views |
| `operations` | `occ_operations` | Operations service data |
| `planning` | `occ_planning` | Planning service data |
| `bunching` | `occ_bunching` | Bunching observations, retry records, alerts, and alert events |

`occ_bunching` can read `ingestion.bunching_snapshot` and `ingestion.bunching_route_direction`. It cannot read or write the underlying ingestion tables.

## Prerequisites

Install Docker Desktop and Java 25, and make sure Docker Desktop is running. The first-time setup needs a root `.env` file. Values must be unquoted because Docker Compose and Spring both read them.

The required database values are:

```text
POSTGRES_PASSWORD=<local admin password>
OCC_INGESTION_DB_PASSWORD=<ingestion password>
OCC_OPERATIONS_DB_PASSWORD=<operations password>
OCC_PLANNING_DB_PASSWORD=<planning password>
OCC_BUNCHING_DB_PASSWORD=<bunching password>
OCC_BUNCHING_DB_URL=jdbc:postgresql://localhost:5432/occ
OCC_BUNCHING_STALE_AFTER=180s
PGADMIN_DEFAULT_EMAIL=<pgAdmin login email>
PGADMIN_DEFAULT_PASSWORD=<pgAdmin login password>
```

## Start (repository root)

Create the root `.env` from `.env.example` if it does not exist, then fill in its values.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose --env-file .env -f infra/local/compose.yaml up -d
docker compose --env-file .env -f infra/local/compose.yaml ps
```

```bash
[ -f .env ] || cp .env.example .env
docker compose --env-file .env -f infra/local/compose.yaml up -d
docker compose --env-file .env -f infra/local/compose.yaml ps
```

Wait for `occ-postgres` to be healthy. Compose reads the root `.env` via `--env-file`; only the listed database variables enter the container. The DataMall key is for ingestion, not PostgreSQL.

## Provision the bunching schema and read access

The database container runs its initialization scripts only on its first start. `02_bunching_schema.sh` also runs then when `OCC_BUNCHING_DB_PASSWORD` is configured, but it is safe to rerun against an existing volume. Run the following **after PostgreSQL is healthy**:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml exec -T postgres bash /docker-entrypoint-initdb.d/02_bunching_schema.sh
```

```bash
docker compose --env-file .env -f infra/local/compose.yaml exec -T postgres \
  bash /docker-entrypoint-initdb.d/02_bunching_schema.sh
```

Next, start ingestion once so Flyway applies `V007__expose_bunching_read_contract.sql`. It creates the two read-only views consumed by the bunching service. After `V007` has completed, grant the bunching role access:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml exec -T postgres bash /docker-entrypoint-initdb.d/03_bunching_read_grants.sh
```

```bash
docker compose --env-file .env -f infra/local/compose.yaml exec -T postgres \
  bash /docker-entrypoint-initdb.d/03_bunching_read_grants.sh
```

Run the grants command again whenever a fresh volume was initialized before ingestion applied `V007`. It grants access only to the two views; this is intentional cross-service isolation.

## Apply service migrations

For Bash, the helper starts PostgreSQL, ingestion, waits for `V007`, then starts the bunching detector:

```bash
bash scripts/start-local.sh
```

`start-local.sh` is a Bash convenience script: it loads the root `.env`, verifies database health, waits for ingestion migration `V007`, grants the detector its read-only views, then starts the detector. It stops both Java services when either exits and never deletes or resets the PostgreSQL volume. PowerShell users should follow the two-terminal steps below.

To use it for the first time, run these commands from the repository root:

```bash
# Create .env once and fill in its passwords, DataMall key, and bunching values.
[ -f .env ] || cp .env.example .env

# Provision the bunching account/schema. This is safe to repeat.
docker compose --env-file .env -f infra/local/compose.yaml up -d postgres
docker compose --env-file .env -f infra/local/compose.yaml exec -T postgres \
  bash /docker-entrypoint-initdb.d/02_bunching_schema.sh

# Starts ingestion, waits for V007, grants read-view access, then starts the detector.
bash scripts/start-local.sh
```

On later runs, the schema, migrations, and grants remain in the volume, so `bash scripts/start-local.sh` is sufficient.

For PowerShell, use two terminals. In the first terminal, start ingestion and wait for its Flyway logs to report migration `007` as successful:

```powershell
Set-Location services/ingestion
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

In a second terminal, from the repository root, run the grants command above. Then load the root `.env` into the current PowerShell process and start the bunching detector:

```powershell
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*([^#=\s]+)=(.*)$') {
    Set-Item -Path "Env:$($matches[1])" -Value $matches[2]
  }
}
.\services\ingestion\mvnw.cmd -f services\bus-bunching-detection\pom.xml spring-boot:run
```

Use Ctrl+C to stop either Java service. Stopping it does not remove database data.

## Browser dashboard (pgAdmin)

Set `PGADMIN_DEFAULT_EMAIL`, `PGADMIN_DEFAULT_PASSWORD` and optionally `PGADMIN_PORT` in the root `.env`, then run from the repository root:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml --profile tools up -d pgadmin
```

```bash
docker compose --env-file .env -f infra/local/compose.yaml --profile tools up -d pgadmin
```

Open http://localhost:5050 and log in with `PGADMIN_DEFAULT_EMAIL` and `PGADMIN_DEFAULT_PASSWORD`. The dashboard is bound to this computer's loopback interface.

Expand **OCC Local → OCC Ingestion** and enter `OCC_INGESTION_DB_PASSWORD` from the root `.env` when prompted to inspect ingestion tables. **OCC Local → OCC Bunching** uses the `occ_bunching` account and `OCC_BUNCHING_DB_PASSWORD`; use it to inspect `bunching.bunching_observation`, `alert_state`, and `alert_event`. Right-click a table and select **View/Edit Data → First 100 Rows**.

The preconfigured connections use Docker's `postgres` hostname and internal port 5432. If you override the database name, update their Maintenance database in pgAdmin. Dashboard settings persist in a separate volume. Initial login settings and server definitions apply on first initialization; changing their source files does not reset an existing dashboard account.

If **OCC Bunching** is not in an already-initialized dashboard, right-click **Servers**, choose **Register → Server…**, and enter the following under **Connection**:

```text
Host name/address: postgres
Port: 5432
Maintenance database: occ
Username: occ_bunching
Password: value of OCC_BUNCHING_DB_PASSWORD in the root .env
```

To inspect data with pgAdmin's **Query Tool**, connect as `occ_bunching` and run:

```sql
SELECT * FROM bunching.bunching_observation ORDER BY evaluated_at DESC LIMIT 100;
SELECT * FROM bunching.alert_state;
SELECT * FROM bunching.alert_event ORDER BY triggered_at DESC LIMIT 100;
SELECT * FROM ingestion.bunching_snapshot ORDER BY fetched_at DESC LIMIT 100;
```

An empty `bunching_observation` table means the detector has not processed a qualifying ingestion snapshot yet. Check `ingestion.bunching_snapshot` first to confirm source observations are available.

To stop just the dashboard:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml stop pgadmin
```

```bash
docker compose --env-file .env -f infra/local/compose.yaml stop pgadmin
```

## Initialization versus migrations

`postgres/init/01_schemas_and_roles.sh` runs only when the database volume is empty. It creates PostGIS plus the `ingestion`, `operations`, and `planning` schemas. `02_bunching_schema.sh` creates the `bunching` schema and `occ_bunching` login when its password is configured. These scripts create no application tables.

Each runnable service applies its own `src/main/resources/db/migration/V*.sql` using Flyway. Do not manually run those files or edit already-applied migrations. Add a new migration for a table change.

Editing the initialization script or changing passwords in `.env` does not update an existing database. Update existing accounts explicitly when rotating credentials. Ordinary application/build changes do not require recreating the volume.

## Inspect and stop

```powershell
docker exec occ-postgres psql -U occ_admin -d occ -c '\dn'
docker exec occ-postgres psql -U occ_admin -d occ -c 'select postgis_version();'
docker compose --env-file .env -f infra/local/compose.yaml stop
```

```bash
docker exec occ-postgres psql -U occ_admin -d occ -c '\dn'
docker exec occ-postgres psql -U occ_admin -d occ -c 'select postgis_version();'
docker compose --env-file .env -f infra/local/compose.yaml stop
```

Use your configured administrator and database names if overridden. `stop` preserves the database. Avoid `down -v` unless you deliberately intend to erase local data.

The old root Compose file used a separate database and volume. Retiring its files does not transfer or delete its data. The canonical Compose project directory and volume declaration have not changed.
