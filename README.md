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

### App commands

Use the launchers under `scripts/`. They find the repository root automatically,
load its `.env` through Compose, and select the local Compose files explicitly.
The default Compose project is `local`; existing `COMPOSE_PROJECT_NAME` settings
are honored. Use the same project for every action so you manage the intended
containers and database.

| Action | PowerShell | Bash | Behavior |
| --- | --- | --- | --- |
| Start app | `.\scripts\app.ps1 start` | `bash scripts/app.sh start` | Build application/migration images as needed, start the app and wait for health checks |
| Stop app | `.\scripts\app.ps1 stop` | `bash scripts/app.sh stop` | Stop project containers, including pgAdmin; preserve containers, networks and data volumes |
| Reset app | `.\scripts\app.ps1 reset` | `bash scripts/app.sh reset` | Delete project containers/networks and database/pgAdmin volumes, rebuild images without layer cache, then start the app |
| Start pgAdmin | `.\scripts\app.ps1 start-pgadmin` | `bash scripts/app.sh start-pgadmin` | Start pgAdmin and its PostgreSQL dependency; leave the app otherwise unchanged |
| Stop pgAdmin | `.\scripts\app.ps1 stop-pgadmin` | `bash scripts/app.sh stop-pgadmin` | Stop only pgAdmin; preserve PostgreSQL, the app and pgAdmin data |

**Reset permanently deletes the selected project's database and pgAdmin data.**
It requires typing `RESET` before deletion. For deliberate non-interactive resets,
use `-Yes` in PowerShell or `--yes` in Bash. Reset rebuilds the project's images;
it does not perform a global Docker prune. pgAdmin is optional and is not started
after reset unless separately requested or enabled by your Compose profiles.

To select a different local project:

PowerShell:

```powershell
.\scripts\app.ps1 start -ProjectName occ-dev
```

Bash:

```bash
bash scripts/app.sh start --project occ-dev
```

Repeat that project option for stop, reset and pgAdmin actions. Use `-Help` /
`--help` to see launcher usage. The launchers require Docker on PATH and a running
Linux container engine. PowerShell launchers support Windows PowerShell 5.1+ and
PowerShell 7; Bash launchers support Linux, macOS, WSL and Git Bash.

PostgreSQL starts first, bootstrap configures database roles, and Flyway applies
migrations before the services start. Expect PostgreSQL, Ingestion, Disruption,
Planning, Gateway and Nginx to be healthy. Bootstrap and migration containers
should show `Exited (0)`; they are one-shot jobs.

### Development and troubleshooting

After Java, dependency, Dockerfile or migration changes, run the start action
again to rebuild changed images. To rebuild only Gateway, use Compose directly:

```shell
docker compose --env-file .env up -d --build gateway
```

Frontend files under `frontend/static/` are mounted read-only into Nginx.
After editing HTML or JavaScript, reload the browser; no Java rebuild is needed.
After changing an Nginx configuration template, restart Nginx to regenerate its
configuration. After changing Compose environment settings, run the start action
to recreate affected containers.

```shell
docker compose --env-file .env restart nginx
docker compose --env-file .env ps -a
docker compose --env-file .env logs --tail 100 nginx gateway ingestion disruption planning
```

If startup fails, inspect `ps -a` and the failed service's logs. For a migration
failure, inspect its one-shot container, for example:

```shell
docker compose --env-file .env logs ingestion-migrate
```

Add `--project-name <name>` to direct Compose commands when managing a custom
project. On memory-constrained engines, run disposable system-test stacks
separately from the normal app and remove their containers afterward.

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
| Ingestion | [http://localhost:6767](http://localhost:6767) | Direct arrival, reference-data and demo API access | Local loopback only; not published in production; operational APIs require ADMIN/CONTROLLER |
| Disruption | [http://localhost:6768](http://localhost:6768) | Direct bunching and traffic-disruption API access | Local loopback only; not published in production; operational APIs require ADMIN/CONTROLLER |
| Planning | [http://localhost:6769](http://localhost:6769) | Direct diagnosis and recommendation API access | Local loopback only; not published in production; operational APIs require ADMIN/CONTROLLER |
| Gateway | [http://localhost:6770](http://localhost:6770) | Direct API routing and authentication debugging | Local loopback only; not published in production; operational APIs require ADMIN/CONTROLLER |
| pgAdmin | [http://localhost:6700](http://localhost:6700) | Inspect the local PostgreSQL database | Local `tools` profile only; not published in production; separate pgAdmin login |

Backend base URLs are API origins, not landing pages; append a documented API
path. Opening a protected API in the address bar does not attach a bearer token.
Nginx is the main browser entry point. Gateway port `6770` is reachable only
through the local development override; production does not publish it. Production
clients access Gateway APIs through Nginx, not through a direct Gateway URL.

The following routes are intentionally inaccessible through Nginx, even with an
ADMIN token:

| Route / endpoint | Nginx response | Reason |
| --- | --- | --- |
| `/internal` and `/internal/**` | `404` in local and production | Private service-to-service APIs; no public Gateway route |
| `/actuator` and `/actuator/**` | `404` in local and production | Health, metrics and management access is separate from the public app |
| `/v3/api-docs` and `/v3/api-docs/**` | `404` in local and production | OpenAPI documents are restricted to direct development service access |
| `/swagger-ui.html` and `/swagger-ui/**` | `404` in local and production | Swagger is a developer interface, not a public app page |
| `/auth-test` and `/auth-test/**` | `404` in production; available locally | Temporary authentication testing only |

Direct backend, Gateway, PostgreSQL and pgAdmin ports are not published by the
production configuration. Local development port access still follows each
service's authentication rules; for example, readiness is anonymous on the
direct service port, whereas Prometheus requires ADMIN. Protected operational
APIs return `401` for missing/invalid tokens and `403` for insufficient roles.
For metrics endpoints, see [Monitoring](docs/MONITORING.md).

Start optional pgAdmin with `.\scripts\app.ps1 start-pgadmin` (PowerShell)
or `bash scripts/app.sh start-pgadmin` (Bash).

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
