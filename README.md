# OCC (Semi-autonomous Operations Control Center)

An agentic dispatch and incident mitigation system for Singapore public bus operations (SMRT / SBS Transit / LTA Bus 4.0) that correlates live transit telemetry, road incidents, weather hazards, and rail disruptions to facilitate orchestration of real-time fleet adjustments. Each Spring Boot service owns its code, database schema and migrations.

## Local quick start
Read the [local development guide](docs/LOCAL-DEVELOPMENT.md).

The production edge, immutable image inputs, and TLS file mapping are prepared
in [`infra/compose.production.yaml`](infra/compose.production.yaml); set real
registry digests and operator-managed certificates before deploying.

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
