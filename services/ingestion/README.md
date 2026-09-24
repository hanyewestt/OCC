# OCC Ingestion Service

## Overview
This service is responsible for polling Bus Arrival predictions and logging them (the "retrospective data" for analytics and ML).

## Requirements
- Java 25
- Docker Desktop with WSL integration enabled
- PostgreSQL database (running locally via Docker Compose at localhost:5432)
- LTA DataMall API Key

## First-time setup

From the repository root in PowerShell:

```powershell
Copy-Item infra\local\.env.example infra\local\.env
docker compose -f infra\local\compose.yaml up -d
```

The database password in `infra/local/.env` is local-only. Do not commit that file.

Set the DataMall key in the same PowerShell window. Keep it out of Git:

```powershell
$env:DATAMALL_API_KEY = "your-real-api-key"
$env:OCC_INGESTION_STALE_AFTER_SECONDS = "120" # Local demo value; choose operational freshness explicitly.
```

## Running from PowerShell

Use Gradle 9.6.0, or the Gradle wrapper when its JAR is available. This checkout does not contain `gradle/wrapper/gradle-wrapper.jar`, so the commands below use installed Gradle:

```powershell
cd services\ingestion
$env:OCC_INGESTION_DB_PASSWORD = ((Get-Content ..\..\infra\local\.env | Where-Object { $_ -like "OCC_INGESTION_DB_PASSWORD=*" }) -split "=", 2)[1].Trim()
gradle bootRun
```

If the API key is stored in `.env`, add this line first:

```env
DATAMALL_API_KEY=your-real-api-key
```

Then load it with:

```powershell
$env:DATAMALL_API_KEY = ((Get-Content ..\..\infra\local\.env | Where-Object { $_ -like "DATAMALL_API_KEY=*" }) -split "=", 2)[1].Trim()
```

Alternatively, open the `services/ingestion` folder in IntelliJ IDEA, set `OCC_INGESTION_DB_PASSWORD`, `DATAMALL_API_KEY` and `OCC_INGESTION_STALE_AFTER_SECONDS` in the `IngestionApplication` run configuration, and run the main class.

On later runs, start the database if needed and run the application again:

```powershell
docker compose -f infra\local\compose.yaml up -d
cd services\ingestion
gradle bootRun
```

## Data Seeding
On startup, if the `poll_target` table is empty, the `InitSeeder` will automatically connect to DataMall's `BusRoutes` endpoint to dynamically discover all stops for routes **190** and **972** and insert them into the target list. 

`ArrivalPollingSchedule` checks due targets every second and dispatches them to a bounded worker pool. Each target becomes due again 60 seconds after dispatch. Slow requests do not block discovery or occupy a shared sequential polling loop. Stop the application with `Ctrl+C`.

## WQAP-40 telemetry audit

The automatic polling actor is `SYSTEM:INGESTION`. A poll-run UUID correlates its start, completion, raw observation and downstream detection. `records_received` counts service entries in the response, not vehicles or normalized rows.

1. A short transaction takes a per-target advisory lock, recovers expired polls and claims the current source/scope/minute. It saves `RUNNING` and `POLL_STARTED` together. Concurrent or repeated claims skip the HTTP call.
2. The HTTP adapter fetches Bus Arrival v3 outside a database transaction. It bounds the complete exchange and response size, refuses redirects and validates the UTF-8 JSON envelope, stop code and Services array. Detailed arrival evidence validation remains in the detector.
3. A completion transaction locks the poll. A successful result saves its raw JSON, updates the poll and feed health, and inserts `POLL_COMPLETED` together. Audit-write failure rolls all of those changes back.
4. Upstream failures save safe status/reason/checksum metadata and update health without replacing last-success observations. Failed/unvalidated bodies and exception messages are not retained verbatim because they may reflect credentials. Transport/size-limit failures may have neither status nor a complete checksum.
5. A claimed poll that loses its worker remains visible as `RUNNING` until recovery. `PollRecoverySchedule` checks up to 100 expired polls every 10 seconds, using poll-run metadata independently of enabled targets. Disabled/deleted targets are included; recovery remains enabled when arrival polling is disabled. Each recovery commits failure, health and completion audit together, skips locked rows and fences late completion. Legacy runs without a lease use their start time plus the configured lease duration. Older recovered attempts cannot overwrite newer feed health.
6. The separate detector consumes the successful stored response and saves its own evidence. Its evaluation and rejection/retry audit rows preserve the same poll-run ID. It makes no DataMall calls.

The scheduler uses minute-level idempotency. The same payload received in a later minute remains a new time-stamped observation; repeating completion of one poll produces no duplicate raw/audit rows. Failed polls retry on a later scheduled minute; within-request retries with jitter remain outside this increment.

Flyway V006 adds `ingestion.telemetry_audit` and `poll_run.lease_expires_at`. Deploy ingestion with V006 and the detector with V4/V5; no applied migration is edited. Existing detector SELECT grants remain sufficient. Stop the old ingestion poller before switching to the new implementation: the old release does not participate in leases.

Audit columns:

| Table | Columns |
| --- | --- |
| `ingestion.telemetry_audit` | `id`, `poll_run_id`, `actor`, `action`, `source`, `scope_key`, `occurred_at`, `outcome`, `classification`, `raw_observation_id`, `payload_checksum`, `http_status`, `records_received`, `reason` |
| `bunching.source_processing_audit` | `id`, `raw_observation_id`, `poll_run_id`, `source_key`, `fetched_at`, `actor`, `action`, `occurred_at`, `reason`, `next_attempt_at`, `classification` |

Start events have no raw observation or HTTP response yet. Failed polls can also lack these fields. Source-processing events preserve whatever provenance is still available; a source already removed by retention has only its original observation ID. `SOURCE_DEFERRED` includes the effective retry deadline; `SOURCE_REJECTED` is terminal quarantine. Both are outcomes expressed by the action and reason.

All records are `OBSERVATION_ONLY`. Audit tables and `bunching.bunching_observation` have no foreign keys to source telemetry: deleting raw/normalized rows or poll runs must not delete audit history. Exclude these audit tables from 24-hour telemetry cleanup and manage their retention separately. This change does not implement a cleanup job or tamper-proof storage.

Inspect one poll as an authorized database owner (replace the synthetic UUID):

```sql
SELECT * FROM ingestion.telemetry_audit
WHERE poll_run_id = '00000000-0000-0000-0000-000000000001'
ORDER BY occurred_at, action DESC;

SELECT raw_observation_id, poll_run_id, actor, action, evaluated_at,
       threshold_seconds, bunched_group_count, evidence
FROM bunching.bunching_observation
WHERE poll_run_id = '00000000-0000-0000-0000-000000000001';

SELECT * FROM bunching.source_processing_audit
WHERE poll_run_id = '00000000-0000-0000-0000-000000000001'
ORDER BY occurred_at;
```

## Configuration and verification

Required environment settings: `OCC_INGESTION_DB_PASSWORD`, `DATAMALL_API_KEY`, `OCC_INGESTION_STALE_AFTER_SECONDS`. Align ingestion freshness with `OCC_BUNCHING_STALE_AFTER` in the detector. Defaults: 10-second HTTP timeout, 45-second lease and a 1 MiB response limit; typed properties reject invalid settings.

Dispatch limits are `OCC_INGESTION_MAX_CONCURRENT_REQUESTS` (default 8, maximum 64) and `OCC_INGESTION_MAX_STARTS_PER_SECOND` (default 1, no higher than the worker limit). These are per-instance limits, not a statement of DataMall's account quota. Configure them within the account's allowance and split that budget if running multiple instances. There is no executor backlog: saturated targets remain due, oldest due first; in-flight targets are not submitted again. Large target sets or insufficient quota/capacity can still exceed the minute target interval. The separate seeder's requests also count toward the provider allowance.

`OCC_INGESTION_RECOVERY_ENABLED=false` explicitly disables the recovery sweep; it is independent of the arrival polling switch. The seeder now traverses BusRoutes pages once and collects both routes 190 and 972 in that traversal, deduplicating shared stops.

For a local mock, set `DATAMALL_BASE_URL` to the mock server, supply a synthetic key, set `OCC_INGESTION_SEED_ENABLED=false` and provision synthetic poll targets. `OCC_INGESTION_ARRIVAL_ENABLED=false` disables scheduled arrival polling. Neither switch changes database evidence.

Run `gradle test bootJar` in this directory with Java 25 and Docker. Tests use a loopback mock HTTP server and disposable PostgreSQL 18/PostGIS. Test scope compiles the actual detector sources to exercise the real cross-service database contract; production ingestion has no dependency on the detector. `IngestionWiringIT` starts the real application with network jobs disabled. Docker-less tests skip database cases and do not establish end-to-end proof.

The local Gradle build is retained as previously agreed; new production classes use the package/layering conventions in `docs/CONVENTIONS.md`. The legacy entry point/entities remain for compatibility. The existing seeder creates targets only; static topology import, normalized-row production, ingestion APIs/security and a real-provider/deployment smoke test are still separate work.
