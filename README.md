# OCC (Semi-autonomous Operations Control Center)

An agentic dispatch and incident mitigation system for Singapore public bus operations (SMRT / SBS Transit / LTA Bus 4.0) that correlates live transit telemetry, road incidents, weather hazards, and rail disruptions to facilitate orchestration of real-time fleet adjustments. Each Spring Boot service owns its code, database schema and migrations.

## Local quick start
Read the [local development guide](docs/LOCAL-DEVELOPMENT.md).

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
