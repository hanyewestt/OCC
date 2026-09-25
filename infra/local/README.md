# Local Docker Compose stack

## Prerequisites

- Docker Desktop is installed and running.
- Create the repository-root `.env` from `.env.example`, then set all passwords
  and `DATAMALL_API_KEY`. Do not commit `.env`.

## Start everything

Run from the repository root.

**PowerShell**

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose --env-file .env -f infra/local/compose.yaml up -d --build
docker compose --env-file .env -f infra/local/compose.yaml ps
```

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
**Bash**
```powershell
docker compose --env-file .env -f infra/local/compose.yaml logs -f ingestion bus-bunching-detection
```

```bash
docker compose --env-file .env -f infra/local/compose.yaml logs -f ingestion bus-bunching-detection
```

## Start optional pgAdmin
**PowerShell**
**Bash**
```powershell
docker compose --env-file .env -f infra/local/compose.yaml --profile tools up -d pgadmin
```

```bash
docker compose --env-file .env -f infra/local/compose.yaml --profile tools up -d pgadmin
```

## Stop

Stop the main stack and optional pgAdmin while preserving all local data:
**PowerShell**
**Bash**
```powershell
docker compose --env-file .env -f infra/local/compose.yaml stop
```

```bash
docker compose --env-file .env -f infra/local/compose.yaml stop
```

To stop only pgAdmin:
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
