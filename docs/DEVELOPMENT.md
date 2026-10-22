# Development

Setup, local commands and developer workflows for OCC. Run commands from the
repository root unless a section says otherwise. For app behavior and page
access, see the [app overview](../README.md).

## Prerequisites

Run all commands from the repository root in PowerShell or Bash (Linux, macOS,
WSL, or Git Bash). Docker Compose and Node commands in the shared `shell` blocks
work unchanged in both shells. Environment-file creation and Maven commands
are shown separately where their syntax differs.

Use a running Docker Engine with Linux containers and Docker Compose 2.24.4 or
newer. On Windows/macOS, start Docker Desktop; on Linux, Docker Engine with the
Compose plugin is sufficient.
Docker builds include Java and Maven; a host Java installation is needed only
for running Maven directly. For host builds/tests, use JDK 25 and the root
Maven Wrapper. Use Node.js 22.12+ and npm for the React build, Vite development
server and browser tests. The app start scripts build the frontend on the host, so Node
is required even when Java services are built entirely in Docker.

## First-time configuration

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

## App commands

Use the launchers under `scripts/`. They find the repository root automatically,
load its `.env` through Compose, and select the local Compose files explicitly.
The default Compose project is `local`; existing `COMPOSE_PROJECT_NAME` settings
are honored. Use the same project for every action so you manage the intended
containers and database.

| Action | PowerShell | Bash | Behavior |
| --- | --- | --- | --- |
| Start app | `.\scripts\app.ps1 start` | `bash scripts/app.sh start` | Install/build React assets, build application/migration images, start the app and wait for health checks |
| Stop app | `.\scripts\app.ps1 stop` | `bash scripts/app.sh stop` | Stop project containers, including pgAdmin; preserve containers, networks and data volumes |
| Reset app | `.\scripts\app.ps1 reset` | `bash scripts/app.sh reset` | Delete project containers/networks and database/pgAdmin volumes, rebuild images without layer cache, then start the app |
| Clear app | `.\scripts\app.ps1 clear` | `bash scripts/app.sh clear` | Remove project containers/networks, all project volumes and referenced service images; leave the app stopped |
| Start pgAdmin | `.\scripts\app.ps1 start-pgadmin` | `bash scripts/app.sh start-pgadmin` | Start pgAdmin and its PostgreSQL dependency; leave the app otherwise unchanged |
| Stop pgAdmin | `.\scripts\app.ps1 stop-pgadmin` | `bash scripts/app.sh stop-pgadmin` | Stop only pgAdmin; preserve PostgreSQL, the app and pgAdmin data |

**Reset and clear permanently delete the selected project's database and pgAdmin data.**
Reset requires typing `RESET`, then rebuilds and starts the app. Clear requires
`CLEAR` and leaves it removed, without rebuilding or restarting. For deliberate
non-interactive use, add `-Yes` in PowerShell or `--yes` in Bash.

Clear uses Compose `down --volumes --remove-orphans --rmi all`, including the
optional pgAdmin service. It requests removal of the images referenced by this
project, including supporting images; Docker may refuse an image removal if
another container still uses it. It does not force removal of shared images.
Source files, `.env`, remote Supabase accounts and global Docker build cache
remain intact. Neither action performs a global Docker prune. pgAdmin is not
started after reset unless requested separately or enabled by Compose profiles.

To select a different local project:

PowerShell:

```powershell
.\scripts\app.ps1 start -ProjectName occ-dev
```

Bash:

```bash
bash scripts/app.sh start --project occ-dev
```

Repeat that project option for stop, reset, clear and pgAdmin actions. Use `-Help` /
`--help` to see launcher usage. The launchers require Docker on PATH and a running
Linux container engine. PowerShell launchers support Windows PowerShell 5.1+ and
PowerShell 7; Bash launchers support Linux, macOS, WSL and Git Bash.

PostgreSQL starts first, bootstrap configures database roles, and Flyway applies
migrations before the services start. Expect PostgreSQL, Ingestion, Disruption,
Planning, Gateway and Nginx to be healthy. Bootstrap and migration containers
should show `Exited (0)`; they are one-shot jobs.

## Frontend development

The frontend is served together with the app: the start scripts run `npm ci`
and `npm run build` in `frontend/`, then start the containers. Nginx serves the
resulting `frontend/dist/` read-only. There is no separate React/Vite container.
Open [the app](http://localhost/login), adjusting the port to `OCC_NGINX_PORT`.
The [root README](../README.md#pages-and-access) lists the frontend routes.

The OBU pacing display is the CAPTAIN-only `/captain` screen. It uses built-in
sample gaps; no operational API or live-data polling was added. Day/night mode
is saved in the `occ-obu-theme` browser preference.

The standalone backend-workflow demo is at `/demo.html`. Its source and
sign-in helper live in `frontend/static/`; Vite copies them into `dist/` for
Nginx. Sign in on the demo itself with an ADMIN or CONTROLLER account. This
demo keeps its token in memory and clears it on reload, independently of the
React app's persistent Supabase session. The local auth-test harness remains
retired. The restored demo includes this branch's recommendation review controls.

### Edit with automatic updates

Keep the app containers running for Gateway, authentication configuration and
API requests. In a second terminal, from the repository root:

```shell
cd frontend
npm ci
npm run dev
```

Open [the Vite development server](http://127.0.0.1:5173). Vite updates the
browser while source files change. It proxies `/frontend-config` and `/api/v1`
to Nginx so browser requests remain same-origin.

| Environment variable | Purpose | Example/default |
| --- | --- | --- |
| `OCC_FRONTEND_DEV_PORT` | Vite loopback listener | `5173` |
| `OCC_FRONTEND_EDGE_PUBLIC_URL` | Host-facing Nginx target for Vite's proxy | `http://localhost:80` |

Set these in the root `.env` or shell environment. If `OCC_NGINX_PORT` changes,
update the proxy URL to match. Restart Vite after configuration changes. Vite
reads only the frontend settings for this configuration; private environment
values are not exposed to browser code. All supported settings remain inventoried
in [`.env.example`](../.env.example).

### Build for Nginx without the start scripts

From the repository root:

```shell
cd frontend
npm ci
npm run build
cd ..
docker compose --env-file .env up -d --build --wait
```

Rebuild after frontend source changes when testing through Nginx. Node and Vite
do not run in the deployed app. Deployment must stage the complete `dist/`
artifact; see [deployment](DEPLOYMENT.md) for the production Compose setup.

### Routes and error handling

React renders the login, controller and captain screens. Gateway verifies the
session and page permission before role content appears. Captain uses the
`CAPTAIN` claim. Persistent sessions and local-scope sign-out are
described in [authentication](AUTHENTICATION.md).

`/401` and `/403` are SPA error screens served as HTML with HTTP 200; they do
not replace API responses. APIs keep their actual 401/403 status and bodies.
Unknown website URLs return HTTP 404 with a separately built `404.html` that
reuses the same error layout and needs no auth bootstrap. Missing assets remain
404s, and internal/management routes remain blocked. Keep the React route list
and Nginx's explicit allowlist aligned when adding a screen.

## Development and troubleshooting

After Java, dependency, Dockerfile or migration changes, run the start action
again to rebuild changed images. To rebuild only Gateway, use Compose directly:

```shell
docker compose --env-file .env up -d --build gateway
```

The React build under `frontend/dist/` is mounted read-only into Nginx.
The start scripts run `npm ci` and `npm run build` in `frontend/` first. For
direct Compose commands, build the frontend first; rebuild after source edits.
See [frontend development](#frontend-development) for Vite development and browser tests.
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

## Open the app

Open [the login page](http://localhost/login) using an existing Supabase account.
CONTROLLER opens `/controller`; CAPTAIN opens `/captain`. Other roles receive
an access message. Sessions persist across reloads using the Supabase SDK;
Gateway verifies session and role before protected content appears. Set
`OCC_NGINX_PORT` if port 80 is unavailable. The standalone `/demo.html` remains
available for backend workflows; `/auth-test` and `/obu-pacing.html` return 404.

The controller dashboard has four routes: `/controller` (overview),
`/controller/disruptions`, `/controller/planning`, and `/controller/audit`.
Nginx explicitly allows each route; unknown controller paths still return 404.
All four share Gateway's controller access verification. API permissions remain
enforced by the backend.

Dashboard data loads on entry and manual Refresh. Arrivals are paginated live
records (50 per page); live bunching displays up to 200 open detections and
reports truncation. An active demo switches the detection view to simulation
events, while arrivals stay explicitly live. Detection rows show the reference
stop name (with code retained), direction and Bus 1/2/3 prediction slots from
the saved detection evidence. They never substitute current arrivals for missing
historical predictions. Missing slots show No prediction; invalid evidence shows
Unavailable, and failed stop enrichment does not hide detections.
The Planning page retrieves the
current demo's recommendation and reads/writes its existing decision endpoint.
The audit page retrieves that same saved decision, including after reload.
There is no full mitigation queue or historical audit listing endpoint yet;
these views explicitly cover only the current simulation. Traffic disruptions
and AI audit logs are labelled placeholders. Use Start simulation / Return to
live in the dashboard header to call the existing demo-run start/close APIs.
The dashboard refreshes after a mode change. These controls switch the shared
backend detection mode, not just the current browser's view; arrivals remain live.
No new runtime settings, services or migrations are
needed for this dashboard.

The controller browser tests use synthetic Supabase and operational API
responses, including decisions, conflicts, API errors, mobile navigation and
cross-role deep links. They do not create real approval records.

### Local URLs

These are the default local addresses; use the configured ports if changed in `.env`.

| Component | URL / endpoint | Purpose | Access |
| --- | --- | --- | --- |
| React app | [http://localhost/login](http://localhost/login) | Sign-in and exclusive controller/captain pages | Verified CONTROLLER or CAPTAIN role for its corresponding page |
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
| `/auth-test` and `/auth-test/**` | `404` in local and production | Retired temporary authentication page |

Direct backend, Gateway, PostgreSQL and pgAdmin ports are not published by the
production configuration. Local development port access still follows each
service's authentication rules; for example, readiness is anonymous on the
direct service port, whereas Prometheus requires ADMIN. Protected operational
APIs return `401` for missing/invalid tokens and `403` for insufficient roles.
For metrics endpoints, see [Monitoring](MONITORING.md).

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

### Frontend checks

From `frontend/`:

```shell
npm run lint
npm run typecheck
npm run build
npx playwright install chromium
npm test
```

The browser suite mocks Supabase/Gateway responses and starts Vite by default.
It proves frontend behavior, not provider behavior or backend authorization.
To run it against the built app served by Nginx, set the test target first:

PowerShell:

```powershell
$env:OCC_FRONTEND_TEST_PUBLIC_URL = 'http://127.0.0.1'
npm test
```

Bash:

```bash
OCC_FRONTEND_TEST_PUBLIC_URL=http://127.0.0.1 npm test
```

Use the actual Nginx host port if changed. For Vite-targeted tests, its configured
port must match `OCC_FRONTEND_DEV_PORT`. Browser test deadlines use
`OCC_FRONTEND_TEST_TIMEOUT_MS` (30000) and
`OCC_FRONTEND_TEST_EXPECT_TIMEOUT_MS` (5000); these defaults are inventoried in
the root `.env.example`. Keep test credentials and saved browser sessions out of Git.

From the repository root, verify real Nginx routing and API boundaries with:

```shell
python tests/system/nginx_edge_smoke.py --base-url http://127.0.0.1
```

For opt-in live Supabase sign-in, refresh, role checks and local sign-out, use
`tests/system/supabase_live_auth_smoke.mjs` with the environment settings described
in [authentication](AUTHENTICATION.md). Ordinary browser tests need no live accounts.
See [testing](TESTING.md) for system tests and CI.

## Related guides

- [Authentication](AUTHENTICATION.md): public configuration, roles and sessions.
- [Testing](TESTING.md): service tests, browser tests and CI.
- [Deployment](DEPLOYMENT.md): container topology and production HTTPS.
- [Monitoring](MONITORING.md): metrics and diagnostic access.
- [Frontend conventions](frontend-convention.md) and [service conventions](CONVENTIONS.md).

### Captain live-auth test configuration

Use `OCC_SUPABASE_TEST_CAPTAIN_EMAIL` and `OCC_SUPABASE_TEST_CAPTAIN_PASSWORD`
from root `.env.example` for the CAPTAIN account. The smoke runner expects the
actual JWT role to be CAPTAIN; invoke it with the `CAPTAIN` account label.
See [authentication](AUTHENTICATION.md) for session refresh behavior.
