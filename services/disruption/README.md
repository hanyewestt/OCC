# OCC Disruption

Disruption is the canonical service for bunching detection and traffic incident
detection. The service owns the canonical `disruption` schema and exposes both
bunching alerts and traffic incidents through the same API and security boundary.
Legacy bunching data is migrated by the checked-in Flyway migration; the old
service and schema are not runtime dependencies.
Flyway SQL resources live in `src/main/resources/db/migration`, matching the
other services. Compose migration images and application/test classpaths use
that same directory. Relocating these resources preserves the existing SQL
filenames and contents, so applied migration versions and checksums stay valid.

Every traffic incident `OPENED`, `UPDATED`, and `CLEARED` transition is recorded
with its immutable provider snapshot and a Planning outbox row in one database
transaction. Delivery reuses the durable, idempotent Planning dispatcher used by
bunching. Traffic transitions keep their own typed event facts and are never
sent through bunching-specific recommendation rules.

From the repository root, verify the service with the root Maven Wrapper:

```powershell
.\mvnw.cmd -pl services/disruption verify
```

```bash
./mvnw -pl services/disruption verify
```

For a host-native local run, create the ignored root `.env` using
[`.env.example`](../../.env.example), then start the local PostgreSQL stack from
the root using [the local setup guide](../../docs/LOCAL-DEVELOPMENT.md). Run the app
from its service directory so `application-local.yml` imports the root `.env`:

```powershell
Set-Location services/disruption
..\..\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

```bash
cd services/disruption
../../mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The service binds to `OCC_DISRUPTION_INTERNAL_PORT` in Compose and uses
`OCC_DISRUPTION_PORT` for host-facing development access. Its PostgreSQL and
peer URLs use distinct internal/public variables so Compose traffic uses service
DNS while host-native runs use `localhost`.

Operational parameters—such as detection thresholds (`occ.disruption.threshold: 90s`),
stale limits (`120s`), headway baselines (`600s`), worker delivery intervals, batch sizes,
and hazard association/absence rules—are configured with safe canonical defaults in
`application.yml`. They can be overridden via standard Spring Boot relaxed binding (`OCC_*`
environment variables) when necessary. Deployment credentials, ports, and peer tokens are
inventoried in the repository-root `.env.example`.
Existing aliases, including `OCC_DISRUPTION_HAZARD_ENABLED`, remain supported.
Inject optional Compose overrides into the service's `environment` with an
additional Compose file; placing them only in the root `.env` does not forward them.

To refresh the checked-in OpenAPI contract, start the local Compose stack, put a
short-lived ADMIN or CONTROLLER access token in `OCC_OPENAPI_EXPORT_TOKEN` for the
current shell only, then run from the repository root:

```powershell
$env:OCC_OPENAPI_EXPORT_TOKEN = '<short-lived access token>'
.\mvnw.cmd -pl services/disruption -Popenapi org.springdoc:springdoc-openapi-maven-plugin:1.5:generate "-Dopenapi.apiDocsUrl=http://localhost:6768/v3/api-docs"
Remove-Item Env:OCC_OPENAPI_EXPORT_TOKEN
```

This updates `services/disruption/openapi/disruption.json` from the running API.
Never save the token in `.env` or commit it.

### Shared Planning data scope

The existing consumer selection also controls Planning's active data scope.
Known-direction live bunching and simulation bunching require two fresh, distinct
poll confirmations before an event is submitted to Planning. Each submission retains
its observation IDs and classification; live events have no demo run ID. The expected
headway baseline uses `OCC_DISRUPTION_EXPECTED_HEADWAY` in both modes. Candidates
older than `OCC_DISRUPTION_STALE_AFTER` or predating a return to Live are discarded.
Unknown-direction detections remain observational alerts and are not sent for diagnosis.

### Multi-hazard lifecycle and candidate exposure

The disruption service consumes the independent flood, rail and approved
roadworks polls only when `OCC_DISRUPTION_HAZARD_ENABLED=true`. Keep the feed
disabled until Ingestion and Disruption have both been deployed with the new
contracts. `OCC_DISRUPTION_HAZARD_POLL_INTERVAL` controls consumption;
`OCC_DISRUPTION_HAZARD_STALE_AFTER` reports feed freshness without clearing an
incident. Feed status is included in `GET /api/v1/disruption/hazards`.

Roadworks are retired by absence only after both
`OCC_DISRUPTION_HAZARD_ABSENCE_LIMIT` complete snapshots and
`OCC_DISRUPTION_HAZARD_ABSENCE_PERIOD` have elapsed. Incomplete/failed polls do
not count. Traffic uses its own `OCC_DISRUPTION_TRAFFIC_CLEAR_AFTER_SUCCESSFUL_ABSENCES`
and `OCC_DISRUPTION_TRAFFIC_MINIMUM_ABSENCE_PERIOD` rules. Flood cancellation,
rail recovery and documented source expiry have their own lifecycle handling.
Absence is reported as “No longer reported,” never as verified clearance.

Cleared hazard identities are retained for `OCC_DISRUPTION_HAZARD_STATE_RETENTION`
(30 days by default). This bounds per-poll state scans while suppressing stale
source replays throughout that window; the immutable disruption events remain
available after the operational state row is removed.

Candidate stop proximity uses `OCC_DISRUPTION_HAZARD_ASSOCIATION_RADIUS_KM` and
the separately imported station references from Ingestion. Associations are
evidence for controller review only; they do not confirm road impact, safety,
closure or available capacity. In simulation mode, downstream stop occurrences
are compared with the assigned synthetic fleet progress. The controller API
remains available at `GET /api/v1/disruption/hazards` and
`GET /api/v1/disruption/hazards/{id}`; Planning reads the latter through a
directed peer capability.
