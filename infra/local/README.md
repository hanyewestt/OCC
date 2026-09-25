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
| Operations demo API (optional `demo` profile) | `http://localhost:6768/swagger-ui.html` |
| Draft demo map (optional `demo` profile) | `http://localhost:6768/demo-map.html` |
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

## Start the draft demo fleet API

Set `OCC_COGNITO_ISSUER_URI` and `OCC_COGNITO_APP_CLIENT_ID` in `.env` for your
Cognito user pool and app client, then run:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml --profile demo up -d --build operations
```

The Operations API creates draft simulated vehicles and assignments only. It
does not yet capture live observations or issue driver directives.

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
