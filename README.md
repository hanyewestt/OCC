# OCC (Semi-autonomous Operations Control Center)

An agentic dispatch and incident mitigation system for Singapore public bus operations (SMRT / SBS Transit / LTA Bus 4.0) that correlates live transit telemetry, road incidents, weather hazards, and rail disruptions to facilitate orchestration of real-time fleet adjustments. Each Spring Boot service owns its code, database schema and migrations.

## Local development: setup, build and start

Run all commands from the repository root in PowerShell or Bash (Linux, macOS,
WSL, or Git Bash). Docker Compose and Node commands in the shared `shell` blocks
work unchanged in both shells. Environment-file creation and Maven commands
are shown separately where their syntax differs.

Use a running Docker Engine with Linux containers and Docker Compose 2.24.4 or
newer. On Windows/macOS, start Docker Desktop; on Linux, Docker Engine with the
Compose plugin is sufficient.
Docker builds include Java and Maven; a host Java installation is needed only
for running Maven directly. For host builds/tests, use JDK 25 and the root
Maven Wrapper. Node.js 20.6 or newer is needed for the optional browser-auth
and live Supabase test scripts.

### First-time configuration

Create the ignored root `.env` only if it does not already exist:

PowerShell:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Bash:

```bash
if [ ! -f .env ]; then cp .env.example .env; fi
```

Fill in the settings described in `.env.example`:

- Database administrator, service runtime and migration passwords; use unique
  migrator passwords and separate service credentials.
- Six directed service peer tokens.
- `SUPABASE_PROJECT_URL`, `SUPABASE_JWKS_URL`, signing algorithm and the public
  `SUPABASE_SB_PUBLISHABLE` key. Use the same project that contains your test
  accounts and `occ_role` hook.
- `DATAMALL_API_KEY`, `OCC_PLANNING_GEMINI_API_KEY` and the intended Planning
  worker/model settings.

Keep one entry per environment-variable name. Duplicate example values later
in `.env` can override real settings. Keep `.env` out of Git. The example file
contains placeholders, so copying it alone does not configure live providers.

### Build and start

Check Docker and existing Compose projects first. Reuse the project already
running this checkout; if it was created with `-p <name>`, keep that same option
in subsequent commands. The default project name is `local`.

```shell
docker info
docker compose ls
docker compose --env-file .env config -q
docker compose --env-file .env up -d --build --wait
docker compose --env-file .env ps -a
```

The automatically loaded `compose.override.yaml` builds the four applications
and three migration images, publishes local development ports, and starts
Nginx. PostgreSQL starts first, bootstrap configures database roles, and Flyway
applies migrations before the application services start. Expect PostgreSQL,
Ingestion, Disruption, Planning, Gateway and Nginx to be healthy. Bootstrap and
migration containers should show `Exited (0)`; they are one-shot jobs.

### Daily development

Start existing images without rebuilding:

```shell
docker compose --env-file .env up -d --wait
```

After Java, dependency, Dockerfile or migration changes, rebuild and start:

```shell
docker compose --env-file .env up -d --build --wait
```

To rebuild just one changed application, for example Gateway:

```shell
docker compose --env-file .env up -d --build gateway
```

Frontend files under `frontend/static/` are mounted read-only into Nginx.
After editing HTML or JavaScript, reload the browser; no Java rebuild is needed.
After changing an Nginx configuration template, restart Nginx to regenerate its
configuration. After changing Compose environment settings, use `up -d` so
Compose can recreate affected containers.

```shell
docker compose --env-file .env restart nginx
docker compose --env-file .env logs --tail 100 nginx gateway ingestion disruption planning
```

If startup fails, inspect `ps -a` and the failed service's logs. For a migration
failure, inspect its one-shot container, for example:

```shell
docker compose --env-file .env logs ingestion-migrate
```

Stop the stack while retaining database data:

```shell
docker compose --env-file .env down --remove-orphans
```

Do not add `-v` unless you intentionally want to erase the local database.
Run disposable system-test stacks separately from the normal app on a
memory-constrained Docker engine, and remove their containers afterward.

### Open the app

Open [the demo](http://localhost/demo.html) and sign in with a configured
Supabase ADMIN or CONTROLLER account. The current development login keeps its
token in page memory, so reloading or switching pages requires signing in again.
The temporary [auth-test page](http://localhost/auth-test/) is available locally.
Set `OCC_NGINX_PORT` if local HTTP port `80` is unavailable.

#### Local URLs

These are the default local addresses; use the configured ports if changed in `.env`.

| Component | URL / endpoint | Purpose | Access |
| --- | --- | --- | --- |
| Demo | [http://localhost/demo.html](http://localhost/demo.html) | Run the simulation and inspect live observations and recommendations | Page is public; data and controls require ADMIN or CONTROLLER sign-in |
| Authentication test | [http://localhost/auth-test/](http://localhost/auth-test/) | Test Supabase sign-in and protected API requests | Local development only; API permissions depend on the signed-in role |
| Nginx public API | `http://localhost/api/v1/...` | Main entry point for application API requests | ADMIN/CONTROLLER bearer token for existing operational APIs |
| Ingestion | [http://localhost:6767](http://localhost:6767) | Direct arrival, reference-data and demo API access | Loopback development interface; operational APIs require ADMIN/CONTROLLER |
| Disruption | [http://localhost:6768](http://localhost:6768) | Direct bunching and traffic-disruption API access | Loopback development interface; operational APIs require ADMIN/CONTROLLER |
| Planning | [http://localhost:6769](http://localhost:6769) | Direct diagnosis and recommendation API access | Loopback development interface; operational APIs require ADMIN/CONTROLLER |
| Gateway | [http://localhost:6770](http://localhost:6770) | Direct API routing and authentication debugging | Loopback development interface; operational APIs require ADMIN/CONTROLLER |
| pgAdmin | [http://localhost:6700](http://localhost:6700) | Inspect the local PostgreSQL database | Optional `tools` profile; separate pgAdmin login |

Backend base URLs are API origins, not landing pages; append a documented API
path. Opening a protected API in the address bar does not attach a bearer token.
Nginx is the main browser entry point. For metrics endpoints, see
[Monitoring](docs/MONITORING.md).

To start optional pgAdmin (PowerShell or Bash):

```shell
docker compose --env-file .env --profile tools up -d pgadmin
```

## Run tests

With Docker running, execute the four-service unit and database integration tests
from the repository root. Host-native Maven tests require JDK 25.

PowerShell:

```powershell
.\mvnw.cmd verify
```

Bash:

```bash
./mvnw verify
```

## Documentation

- [Authentication](docs/AUTHENTICATION.md): development login, roles and session limitations.
- [Testing](docs/TESTING.md): browser checks, system tests, load tests and CI.
- [Deployment](docs/DEPLOYMENT.md): container topology, production images and HTTPS setup.
- [Monitoring](docs/MONITORING.md): protected Prometheus endpoints and available metrics.
- [Service conventions](docs/CONVENTIONS.md): code structure and configuration rules.
