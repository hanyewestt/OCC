# OCC (Semi-autonomous Operations Control Center)

An agentic dispatch and incident mitigation system for Singapore public bus operations (SMRT / SBS Transit / LTA Bus 4.0) that correlates live transit telemetry, road incidents, weather hazards, and rail disruptions to facilitate orchestration of real-time fleet adjustments. Each Spring Boot service owns its code, database schema and migrations.

## Local quick start

Start Docker Desktop. Java and Maven are not required on the host for the Compose workflow. From the repository root, create `.env` only if it does not already exist:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

```bash
[ -f .env ] || cp .env.example .env
```

Fill in its passwords and DataMall key using unquoted values, then build and start the complete stack:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml up --build -d
docker compose --env-file .env -f infra/local/compose.yaml ps
```

```bash
docker compose --env-file .env -f infra/local/compose.yaml up --build -d
docker compose --env-file .env -f infra/local/compose.yaml ps
```

This starts PostgreSQL, applies database bootstrap and Flyway migrations, starts ingestion, grants the detector its read-only ingestion views, and starts bus-bunching detection. Open the ingestion API at `http://localhost:<OCC_INGESTION_PORT>/swagger-ui.html` (the default is `8080`). `OCC_INGESTION_PORT` is the published host port; Compose services use the private `ingestion:<OCC_INGESTION_INTERNAL_PORT>` endpoint instead.

To include pgAdmin, add `--profile tools` to `up`; then open http://localhost:5050. To follow all application logs:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml logs -f ingestion bus-bunching-detection
```

```bash
docker compose --env-file .env -f infra/local/compose.yaml logs -f ingestion bus-bunching-detection
```

Stop the containers with `docker compose --env-file .env -f infra/local/compose.yaml down`. The database volume is retained unless `-v` is explicitly supplied.

## Repository structure

| Location | Responsibility |
| --- | --- |
| `infra/local/compose.yaml` | The complete local stack: PostgreSQL/PostGIS, ingestion, bus-bunching detection, and optional pgAdmin |
| `infra/local/postgres/init/` | Initial creation of accounts, schema namespaces and PostGIS |
| `services/ingestion/` | Runnable Spring Boot app; owns ingestion tables and DataMall polling |
| `services/operations/` | Operations migration scaffolding; not yet a runnable app |
| `services/planning/` | Planning migration scaffolding; not yet a runnable app |
| `services/<name>/src/main/resources/db/migration/` | That service's versioned Flyway SQL migrations |
| `.env` | Ignored local configuration shared by Compose and local Spring runs |
| `docs/CONVENTIONS.md` | Canonical Java, Maven, package, API and database conventions |

One database (`occ`) contains the `ingestion`, `operations` and `planning` schemas. Each has its own application account. Bootstrap creates empty namespaces; each service runs its own Flyway migrations to create and evolve its tables. Hibernate validates mappings without changing tables.

Compose receives only the database variables explicitly listed in its `environment` section. The DataMall key is not passed to PostgreSQL. Deployment injects each application's own configuration; the local `.env` is not packaged in the JAR or container image.

`/.env.example` is the centralized inventory of local runtime configuration. Copy it
to the ignored `/.env` and configure both services there. Ingestion and bunching
load that file only with their `local` profile; deployment supplies the same values
through its environment or secret store. `SPRING_DATASOURCE_URL` remains accepted
by ingestion for Spring Boot compatibility, but new local configuration should use
`OCC_INGESTION_DB_URL`.

## Build and verification

```powershell
cd services/ingestion
.\mvnw.cmd verify
```

```bash
cd services/ingestion
./mvnw verify
```

Tests use synthetic configuration. Docker-backed integration tests skip when Docker is unavailable. See [ingestion instructions](services/ingestion/README.md), [database operations](infra/local/README.md), and [code conventions](docs/CONVENTIONS.md).

## Migration from the earlier layout

The root `docker-compose.yml` and `database/init/01-schema.sql` have been retired. They configured a separate PostgreSQL 16 database named `occ_db`; their definitions remain in Git history. No Docker volumes are removed or migrated. If the old `occ_db` container occupies port 5432, stop it before starting the canonical stack.

The canonical Compose file remains in `infra/local` with its existing volume declaration so existing local data remains attached. Changing `.env` passwords does not update already-created database accounts.

Ingestion now uses Maven and the `com.csd.occ.ingestion` package. Reimport `services/ingestion/pom.xml` in your IDE. Applied SQL migrations are unchanged. The old local configuration files are preserved as ignored `.env.legacy` backups and are no longer read. To roll back the build/configuration transition, restore the earlier tracked files from Git and rename those backups to `.env`; do not reset database volumes.
