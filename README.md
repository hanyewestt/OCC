# OCC (Semi-autonomous Operations Control Center)

An agentic dispatch and incident mitigation system for Singapore public bus operations (SMRT / SBS Transit / LTA Bus 4.0) that correlates live transit telemetry, road incidents, weather hazards, and rail disruptions to facilitate orchestration of real-time fleet adjustments. Each Spring Boot service owns its code, database schema and migrations.

## Local quick start

Install Java 25 and start Docker Desktop. From the repository root, create `.env` only if it does not already exist:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

```bash
[ -f .env ] || cp .env.example .env
```

Fill in its passwords and DataMall key using unquoted values.

1. Start PostgreSQL and pgAdmin together, then wait for PostgreSQL to be healthy:

   ```powershell
   docker compose --env-file .env -f infra/local/compose.yaml --profile tools up -d
   docker compose --env-file .env -f infra/local/compose.yaml ps
   ```

   ```bash
   docker compose --env-file .env -f infra/local/compose.yaml --profile tools up -d
   docker compose --env-file .env -f infra/local/compose.yaml ps
   ```

   This starts `occ-postgres` and `occ-pgadmin`. Open pgAdmin at http://localhost:5050.

2. Start only ingestion:

   ```powershell
   cd services/ingestion
   .\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
   ```

   ```bash
   cd services/ingestion
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```

The wrapper downloads Maven on first use. Stop the app with Ctrl+C; PostgreSQL stays running.

The Compose command starts the database and dashboard only. To start PostgreSQL, ingestion, and bus-bunching detection together, run `bash scripts/start-local.sh` from the repository root. Press Ctrl+C to stop both Java services; PostgreSQL stays running. Docker Desktop's Docker Engine must be running before using Compose.

The `local` profile automatically reads the root `.env`. Maven sets the working directory to `services/ingestion`. Set the same working directory and active profile `local` in IDE run configurations. If you already have a database, skip Compose and set `OCC_INGESTION_DB_URL` in `.env`. An existing `SPRING_DATASOURCE_URL` override also takes precedence over the default URL.

## Repository structure

| Location | Responsibility |
| --- | --- |
| `infra/local/compose.yaml` | The single local container setup: PostgreSQL/PostGIS |
| `infra/local/postgres/init/` | Initial creation of accounts, schema namespaces and PostGIS |
| `services/ingestion/` | Runnable Spring Boot app; owns ingestion tables and DataMall polling |
| `services/operations/` | Operations migration scaffolding; not yet a runnable app |
| `services/planning/` | Planning migration scaffolding; not yet a runnable app |
| `services/<name>/src/main/resources/db/migration/` | That service's versioned Flyway SQL migrations |
| `.env` | Ignored local configuration shared by Compose and local Spring runs |
| `docs/CONVENTIONS.md` | Canonical Java, Maven, package, API and database conventions |

One database (`occ`) contains the `ingestion`, `operations` and `planning` schemas. Each has its own application account. Bootstrap creates empty namespaces; each service runs its own Flyway migrations to create and evolve its tables. Hibernate validates mappings without changing tables.

Compose receives only the database variables explicitly listed in its `environment` section. The DataMall key is not passed to PostgreSQL. Deployment injects each application's own configuration; the local `.env` is not packaged in the JAR or container image.

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
