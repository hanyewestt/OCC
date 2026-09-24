# Local PostgreSQL

This is the only local Compose setup. It runs PostgreSQL 18 with PostGIS 3.6, one database, three service schemas and three application accounts.

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

## Browser dashboard (pgAdmin)

Set `PGADMIN_DEFAULT_EMAIL`, `PGADMIN_DEFAULT_PASSWORD` and optionally `PGADMIN_PORT` in the root `.env`, then run from the repository root:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml --profile tools up -d pgadmin
```

```bash
docker compose --env-file .env -f infra/local/compose.yaml --profile tools up -d pgadmin
```

Open http://localhost:5050 and log in with `PGADMIN_DEFAULT_EMAIL` and `PGADMIN_DEFAULT_PASSWORD`. The dashboard is bound to this computer's loopback interface.

Expand **OCC Local → OCC Ingestion** and enter `OCC_INGESTION_DB_PASSWORD` from the root `.env` when prompted. Then open **Databases → occ → Schemas → ingestion → Tables**. Right-click a table and select **View/Edit Data → First 100 Rows**.

The preconfigured connection uses Docker's `postgres` hostname and internal port 5432. If you override the database name, update the connection's Maintenance database in pgAdmin. Dashboard settings persist in a separate volume. Initial login settings and server definitions apply on first initialization; changing their source files does not reset an existing dashboard account.

To stop just the dashboard:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml stop pgadmin
```

```bash
docker compose --env-file .env -f infra/local/compose.yaml stop pgadmin
```

## Initialization versus migrations

`postgres/init/01_schemas_and_roles.sh` runs only when the database volume is empty. It creates PostGIS and the `ingestion`, `operations`, and `planning` schemas, each owned by its corresponding `occ_<service>` account. It creates no application tables.

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
