# Local Docker Compose stack

## Prerequisites

- Docker Desktop is installed and running.
- Create the repository-root `.env` from `.env.example`, then set all passwords
  and `DATAMALL_API_KEY`. Do not commit `.env`.

## Start everything

Run from the repository root `yourfolders/OCC/`.

**PowerShell**

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose --env-file .env -f infra/local/compose.yaml up -d --build
docker compose --env-file .env -f infra/local/compose.yaml ps
```

## Database reduction upgrades

Fresh volumes create only the four active service schemas. The PostgreSQL health
check uses TCP so schema jobs cannot run against the entrypoint's temporary
socket-only server. `OCC_OPERATIONS_DB_PASSWORD` and `OCC_PLANNING_DB_PASSWORD`
are deprecated, accepted but unused; keep existing environment names during transition.

For an existing volume, back up first, inspect reserved schemas and role ownership,
and run `postgres/admin/retire_reserved_schemas.sql` as an administrator only after
confirming no external reader uses them. RESTRICT aborts on unexpected dependencies;
never add CASCADE. Global role cleanup is outside service Flyway ownership.

Operational history defaults to 12h across ingestion and bunching. Existing explicit
environment values remain supported. New AI text limits are
`OCC_DIAGNOSIS_AUDIT_MAX_RESPONSE_CHARACTERS` and
`OCC_RECOMMENDATION_AUDIT_MAX_RESPONSE_CHARACTERS`, both 65536 by default.
See [the verification and breaking-change report](../../docs/plans/database-reduction-verification.md).


**Bash**

```bash
[ -f .env ] || cp .env.example .env
docker compose --env-file .env -f infra/local/compose.yaml up -d --build
docker compose --env-file .env -f infra/local/compose.yaml ps
```

## Where to find things

| Component | Host address |
| --- | --- |
| Ingestion API and Swagger UI | `http://localhost:6767/swagger-ui.html` |
| PostgreSQL | `localhost:5432` |
| pgAdmin | `http://localhost:6700` |
| Application logs | See the commands below |

To follow application logs:

**PowerShell**
```powershell
docker compose --env-file .env -f infra/local/compose.yaml logs -f ingestion bus-bunching-detection
```
**Bash**

```bash
docker compose --env-file .env -f infra/local/compose.yaml logs -f ingestion bus-bunching-detection
```

## Start optional pgAdmin
**PowerShell**
```powershell
docker compose --env-file .env -f infra/local/compose.yaml --profile tools up -d pgadmin
```
**Bash**

```bash
docker compose --env-file .env -f infra/local/compose.yaml --profile tools up -d pgadmin
```

## Stop

Stop the main stack and optional pgAdmin while preserving all local data:

**PowerShell**
```powershell
docker compose --env-file .env -f infra/local/compose.yaml stop
```
**Bash**

```bash
docker compose --env-file .env -f infra/local/compose.yaml stop
```

**To stop only pgAdmin:**

**PowerShell**
```powershell
docker compose --env-file .env -f infra/local/compose.yaml stop pgadmin
```
**Bash**

```bash
docker compose --env-file .env -f infra/local/compose.yaml stop pgadmin
```

## Rebuild or reset

Rebuild images and recreate application containers without deleting database data:
**PowerShell**
```powershell
docker compose --env-file .env -f infra/local/compose.yaml up -d --build --force-recreate
```
**Bash**

```bash
docker compose --env-file .env -f infra/local/compose.yaml up -d --build --force-recreate
```

Delete containers, images created by Compose, and all named volumes for a completely
fresh local database. This permanently deletes local OCC data:

**PowerShell**
```powershell
docker compose --env-file .env -f infra/local/compose.yaml down -v --rmi local --remove-orphans
```
**Bash**

```bash
docker compose --env-file .env -f infra/local/compose.yaml down -v --rmi local --remove-orphans
```
