# OCC (Semi-autonomous Operations Control Center)

An agentic dispatch and incident mitigation system for Singapore public bus operations (SMRT / SBS Transit / LTA Bus 4.0) that correlates live transit telemetry, road incidents, weather hazards, and rail disruptions to facilitate orchestration of real-time fleet adjustments. Each Spring Boot service owns its code, database schema and migrations.

## Local quick start
Read the [local development guide](docs/LOCAL-DEVELOPMENT.md).

From the repository root, build and start the local stack with its automatically
loaded development override:

```powershell
docker compose --env-file .env up -d --build
```

The production edge, immutable image inputs, and TLS file mapping are prepared
in [`infra/compose.production.yaml`](infra/compose.production.yaml); set real
registry digests and operator-managed certificates before deploying.
Use the [deployment and recovery runbook](docs/DEPLOYMENT-AND-RECOVERY.md) for
the single-host cutover, backup, restore, and rollback procedure.

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
