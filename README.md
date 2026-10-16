# OCC (Semi-autonomous Operations Control Center)

An agentic dispatch and incident mitigation system for Singapore public bus operations (SMRT / SBS Transit / LTA Bus 4.0) that correlates live transit telemetry, road incidents, weather hazards, and rail disruptions to facilitate orchestration of real-time fleet adjustments. Each Spring Boot service owns its code, database schema and migrations.

## Local development: setup, build and start

Run all commands from the repository root in PowerShell. Install Docker Desktop
with its Linux container engine running and Docker Compose 2.24.4 or newer.
Docker builds include Java and Maven; a host Java installation is needed only
for running Maven directly. For host builds/tests, use JDK 25 and the root
Maven Wrapper. Node.js 20.6 or newer is needed for the optional browser-auth
and live Supabase test scripts.

### First-time configuration

Create the ignored root `.env` only if it does not already exist:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
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

```powershell
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

```powershell
docker compose --env-file .env up -d --wait
```

After Java, dependency, Dockerfile or migration changes, rebuild and start:

```powershell
docker compose --env-file .env up -d --build --wait
```

To rebuild just one changed application, for example Gateway:

```powershell
docker compose --env-file .env up -d --build gateway
```

Frontend files under `frontend/static/` are mounted read-only into Nginx.
After editing HTML or JavaScript, reload the browser; no Java rebuild is needed.
After changing an Nginx configuration template, restart Nginx to regenerate its
configuration. After changing Compose environment settings, use `up -d` so
Compose can recreate affected containers.

```powershell
docker compose --env-file .env restart nginx
docker compose --env-file .env logs --tail 100 nginx gateway ingestion disruption planning
```

If startup fails, inspect `ps -a` and the failed service's logs. For a migration
failure, inspect its one-shot container, for example:

```powershell
docker compose --env-file .env logs ingestion-migrate
```

Stop the stack while retaining database data:

```powershell
docker compose --env-file .env down --remove-orphans
```

Do not add `-v` unless you intentionally want to erase the local database.
Run disposable system-test stacks separately from the normal app on a
memory-constrained Docker engine, and remove their containers afterward.

### Open the app

Nginx starts automatically and serves [the demo](http://localhost/demo.html)
and [the temporary authentication test](http://localhost/auth-test/). Set
`OCC_NGINX_PORT` to change the local loopback HTTP port (default `80`);
`OCC_NGINX_HTTP_INTERNAL_PORT` and `OCC_NGINX_HTTPS_INTERNAL_PORT` configure
the container listeners (defaults `8080` and `8443`). Sign in
on either page with a Supabase test account; ADMIN and CONTROLLER can use the
demo. Tokens stay in that page's memory, so reloads or switching pages require
another sign-in. The demo sends bearer tokens with all protected API calls.
`SUPABASE_SB_PUBLISHABLE` must be an `sb_publishable_...` public key; service-role
and secret keys are rejected. Gateway exposes only the project URL and this
publishable key at `/frontend-config`.

The pages and shared authentication JavaScript live in `frontend/static/` and
are served directly by Nginx. Java services host APIs only. Nginx forwards
`/api/v1/**` to Gateway while preserving bearer authentication, and blocks
internal and management routes. Direct service development ports remain on
loopback (`6767`–`6770`). Docker Compose 2.24.4 or newer is required for the
profile reset used by the edge overrides. Explicit backend test stacks leave
Nginx in the optional `edge` profile, while local and production overrides
activate it automatically.

The production edge, immutable image inputs, and TLS file mapping are prepared
in [`infra/compose.production.yaml`](infra/compose.production.yaml); set real
registry digests and operator-managed certificates before deploying.
Production uses `docker compose --env-file .env -f compose.yaml -f
infra/compose.production.yaml up -d`. Set `OCC_NGINX_SERVER_NAME`, TLS certificate
and private-key file paths, `OCC_NGINX_PUBLIC_PORT` (default `443`), and immutable
application/migration image digests first. The HTTPS configuration serves the
demo and `/auth.js`, and always blocks `/auth-test` and its descendants. Local
HTTP and production TLS listeners reuse `infra/nginx/routes.inc.template`.
Set DNS cache validity (`OCC_NGINX_DNS_VALIDITY`, default `30s`), request limits
and proxy timeouts through the settings documented in `.env.example`.
AWS deployment remains deferred.

For browser authentication checks, run:

```powershell
node --test tests/system/frontend_auth.test.mjs
```

CI also compares Nginx responses byte-for-byte with the static files, checks
role restrictions and hidden routes through the TLS edge, and verifies static
pages remain available with Gateway stopped. Contract migration fixtures live
in `tests/contracts/`; CI does not depend on the ignored `docs/` folder.

## Build and verify the Phase 1 service reactor

The repository-root Maven Wrapper pins Apache Maven 3.9.16. From the repository root,
build and run the unit and integration test lifecycle for the four target services:

```powershell
.\mvnw.cmd verify
```

```bash
./mvnw verify
```

The reactor includes Ingestion, Disruption, Planning, and Gateway. Disruption,
Planning, and Gateway listen on internal ports `8002`, `8003`, and `8004`;
configure them through `OCC_DISRUPTION_INTERNAL_PORT`,
`OCC_PLANNING_INTERNAL_PORT`, and `OCC_GATEWAY_INTERNAL_PORT` in the ignored
repository-root `.env` for local runs. Planning owns both diagnosis and
recommendation generation; its setup and protected API details are in
[`services/planning/README.md`](services/planning/README.md). Environment names
and safe example values are listed in `.env.example`.

The local Compose override builds the service and migration images. Migration
images retain the PostgreSQL JDBC driver and omit unused Databricks, Couchbase,
and SQL Server drivers because this stack migrates PostgreSQL only.

## Operational metrics

The services expose Prometheus-format metrics at `/actuator/prometheus` for
authenticated `ADMIN` callers. Use a direct loopback service URL from the local
Compose override (Ingestion `6767`, Disruption `6768`, Planning `6769`, Gateway
`6770`) and an existing ADMIN access token. The Nginx edge denies management
paths, and the Gateway does not forward them to backend services; the
production Compose package does not publish the backend ports. Do not paste
bearer tokens into source files, command history, or test evidence.

Custom low-cardinality metrics report feed freshness/degradation and arrival
sequence in Ingestion; consumed sequence, deferred work, pending Planning
outbox age, retained attempts, and expired leases in Disruption; and pending
request/job age plus retained model fallback/provider-failure counts in
Planning. Each service also exposes a process-lifetime counter for rows removed
by its scheduled retention cleanup. Standard Spring Boot and Micrometer metrics
include JVM, HTTP server, and database-pool measurements where the service uses
those components. Database gauges query each service's own schema when scraped;
they do not expose event IDs, user IDs, provider prompts, or payloads.

## Phase 1 authorized-query smoke

CI runs [`tests/system/authorized_poll_smoke.js`](tests/system/authorized_poll_smoke.js)
with the pinned Grafana k6 `2.3.0` image and a short synthetic ADMIN-token profile.
The test repeatedly reads `/api/v1/demo-mode` through the TLS edge and checks
response status, shape, p95 latency, and request errors. Its safe profile settings
are listed at the bottom of `.env.example`; never put a real Supabase token there.
This smoke catches regressions in the authenticated read path. It is not a
capacity or retention-soak result; those still require a representative staging host.

The repository secret/misconfiguration job sets the CI-tool-only
`TRIVY_OFFLINE_SCAN=true` to prevent unrelated remote Maven dependency lookups.
It still scans source files and enforces HIGH/CRITICAL findings through the
sanitizer. Separate image vulnerability jobs use current vulnerability databases
and retain their HIGH/CRITICAL failure gates and SBOM output.
The image matrix covers all four applications and all three schema-migration
images; the pinned Nginx image has its own scan and SBOM job.

The isolated CI workflow also routes Disruption-to-Planning submissions through
[`infra/compose.dispatch-test.yaml`](infra/compose.dispatch-test.yaml). Its
test-only proxy drops the first response after Planning returns HTTP 202, then
the smoke restarts both services and verifies the same idempotency key and one
durable Planning request survive. The control port is loopback-only and the
profile must use its own Compose project and database volume.
