# Local OCC stack

The Compose stack runs PostgreSQL/PostGIS, a one-shot idempotent database bootstrap,
Ingestion, Disruption, Planning, Gateway, and optional pgAdmin. Each application
owns one schema. Internal requests use Compose service names and ports; browser
and host-native calls use the published host ports.

## Configure and start

Create the ignored repository-root `.env` only if it does not already exist:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Set the Supabase project URL and JWKS URL, DataMall API key, database passwords,
and six directed service peer tokens in `.env`. Keep the JWT algorithm aligned
with Supabase's active signing key. The local environment inventory and safe
defaults are in [`.env.example`](../../.env.example).

From the repository root, validate and start the stack:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml config -q
docker compose --env-file .env -f infra/local/compose.yaml up -d --build
docker compose --env-file .env -f infra/local/compose.yaml ps
```

The bootstrap runs once per Compose update, safely creates or updates the three
service roles/schemas, and grants read access to existing `bunching`,
`recommendation`, and `diagnosis` schemas only for the one-time Flyway imports.
Application runtime traffic stays behind private service APIs. Existing database
volumes are reused; back them up before the migration.

For an existing Phase 0/legacy stack, first save a database backup and stop the
old consumers without deleting the PostgreSQL volume:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' > .\occ-before-phase1.dump
docker compose --env-file .env -f infra/local/compose.yaml down --remove-orphans
docker compose --env-file .env -f infra/local/compose.yaml up -d --build
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
published in this local-only Compose file to simplify health checks, Swagger
testing and integration work. In Compose, services still call one another using
`http://ingestion:8001`, `http://disruption:8002` and `http://planning:8003`.

Swagger UI is available at each service's `/swagger-ui.html`; backend services
require a valid Supabase token and the allowed `ADMIN` or `CONTROLLER` role.
Gateway health is at `/actuator/health/readiness`. Follow logs with:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml logs -f gateway ingestion disruption planning
```

Start pgAdmin only when needed:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml --profile tools up -d pgadmin
```

Stop containers without removing database data:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml down --remove-orphans
```

Removing Compose volumes with `down -v` permanently erases the local database
and pgAdmin settings; use it only when a fresh empty database is intentional.
