# Local OCC stack

The root Compose stack runs PostgreSQL/PostGIS, one SQL bootstrap job, three
one-shot Flyway migration jobs, Ingestion, Disruption, Planning, Gateway, and
optional pgAdmin. Each service has a non-login schema owner, migration login,
and restricted runtime login. Separate edge, app, database, and egress networks
keep database and backend ports off the public edge. The automatically loaded
local override builds images and publishes development ports on loopback only.

## Configure and start

Create the ignored repository-root `.env` only if it does not already exist:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Set the Supabase project URL and JWKS URL, DataMall API key, Gemini API key for
the enabled diagnosis worker, database runtime and migrator passwords, and six
directed service peer tokens in `.env`. Keep the JWT algorithm aligned with
Supabase's active signing key. The local environment inventory and safe
defaults are in [`.env.example`](../.env.example).
The three `OCC_*_DB_MIGRATOR_PASSWORD` values must be unique development
credentials. `OCC_DB_MIGRATION_CONNECT_RETRIES` controls how long the one-shot
Flyway jobs retry the database connection before failing.

From the repository root, validate and start the stack:

```powershell
docker compose --env-file .env config -q
docker compose --env-file .env up -d --build
docker compose --env-file .env ps
```

Compose waits for PostgreSQL health, runs `infra/postgres/bootstrap.sql`, then
applies each service's versioned migrations with its own migrator credential.
The local override builds the service and migration images; Flyway SQL and
callbacks are packaged into the migration images, so jobs do not rely on
source-directory mounts at runtime.
Bootstrap is repeatable: it preserves existing data, transfers known legacy
schema ownership to non-login owners, and reconciles existing runtime/migrator
passwords with the configured values after validating role attributes. It fails
if it encounters unexpected object ownership. Legacy `bunching`,
`diagnosis`, and `recommendation` schemas are readable by the corresponding
migration owner only; runtime roles cannot read those schemas or run DDL. Existing
database volumes are reused; back them up before migration.

For an existing Phase 0/legacy stack, first save a database backup and stop the
old consumers without deleting the PostgreSQL volume:

```powershell
docker compose --env-file .env exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' > .\occ-before-phase1.dump
docker compose --env-file .env down --remove-orphans
docker compose --env-file .env up -d --build
```

The new service migrations import legacy rows idempotently and record source
hashes in import ledgers. Keep the backup until the existing demo and live
workflow checks pass.

## Local endpoints

| Service | Host URL | Container port |
| --- | --- | ---: |
| Ingestion | `http://localhost:6767` | 8001 |
| Disruption | `http://localhost:6768` | 8002 |
| Planning | `http://localhost:6769` | 8003 |
| Gateway | `http://localhost:6770` | 8004 |
| PostgreSQL | `localhost:5432` | 5432 |
| pgAdmin | `http://localhost:6700` | 80 |

The Gateway API is the user-facing entry point. The service host ports are
published on `127.0.0.1` in `compose.override.yaml` to simplify health checks,
Swagger testing and integration work. In Compose, services still call one another using
`http://ingestion:8001`, `http://disruption:8002` and `http://planning:8003`.

Swagger UI is available at each service's `/swagger-ui.html`; backend services
require a valid Supabase token and the allowed `ADMIN` or `CONTROLLER` role.
Gateway health is at `/actuator/health/readiness`. Follow logs with:

```powershell
docker compose --env-file .env logs -f gateway ingestion disruption planning
```

Start pgAdmin only when needed:

```powershell
docker compose --env-file .env --profile tools up -d pgadmin
```

Stop containers without removing database data:

```powershell
docker compose --env-file .env down --remove-orphans
```

Removing Compose volumes with `down -v` permanently erases the local database
and pgAdmin settings; use it only when a fresh empty database is intentional.

## Production Compose

The production stack uses explicit files so the local override is not merged.
Replace the example image references in `.env` with immutable registry digests,
and set the real DNS name and operator-managed TLS certificate and key paths.
Do not commit TLS files. Validate the resolved deployment configuration before
starting it:

```powershell
docker compose --env-file .env -f compose.yaml -f infra/compose.production.yaml config -q
docker compose --env-file .env -f compose.yaml -f infra/compose.production.yaml up -d
```

For staging cutover, backup, restore rehearsal, and rollback steps, follow the
[deployment and recovery runbook](DEPLOYMENT-AND-RECOVERY.md). Do not treat a
successful Compose parse as a completed deployment or restore test.

Only Nginx publishes the production HTTPS port. It runs as UID 101 without
Linux capabilities and listens on unprivileged container port 8443; Compose maps
the configured public host port (443 by default) to it. It forwards public API paths
through Gateway, serves the existing demo page through Gateway, and rejects
internal, management, OpenAPI, Swagger, and temporary auth-test paths. Gateway
and backend networks retain outbound access for Supabase JWKS, DataMall, and
Gemini; Postgres and migration jobs stay on the internal database network.

Run the disposable no-host-port stack for integration verification with a project
name that is different from the default `local` project. This keeps its database
volume separate from the developer's normal local data:

On a memory-constrained Docker Desktop engine, run one four-service Compose
stack at a time. Check `docker compose ls` and `docker stats --no-stream` before
starting another stack. Stop the development stack while running a disposable
system test, then start it again afterward; use the same project name and Compose
files that originally created it. Stopping containers preserves their database
volumes. Run the Maven/Testcontainers suite separately from image builds.

Always run the test project's `down --remove-orphans` command after verification,
including on failure (use a `finally` block in local automation). Keep normal
Testcontainers/Ryuk cleanup enabled. An exited-successful bootstrap or migration
job belonging to the retained development stack is expected; an old test project
or abandoned Testcontainers database is a cleanup candidate once its test process
has stopped. Remove only identified test resources, without pruning database
volumes or unrelated projects.

```powershell
docker compose -p occ-phase1-check --env-file .env.example -f compose.yaml -f infra/compose.test.yaml up -d --build
docker compose -p occ-phase1-check --env-file .env.example -f compose.yaml -f infra/compose.test.yaml ps
docker compose -p occ-phase1-check --env-file .env.example -f compose.yaml -f infra/compose.test.yaml down --remove-orphans
```
