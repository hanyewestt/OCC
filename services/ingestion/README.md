# OCC Ingestion Service

Polls DataMall bus arrivals and traffic incidents and records observations for analytics. Requires Java 25, PostgreSQL with the `ingestion` schema and `occ_ingestion` account, and a valid DataMall key.

## Demo replay

Demo support is always available at `/demo.html`. Its persisted runtime state starts disabled, so the replay scheduler does no work and bunching remains live until **Show demo** starts a run. **Return to live** switches bunching back to the live feed and closes the run. The mode can also be toggled while ingestion is running; no restart is needed:

```http
GET /api/v1/demo-mode
PUT /api/v1/demo-mode
Content-Type: application/json

{"enabled":true}
```

Setting `enabled` to `true` starts a replay if none is active, or resumes the currently active run. Setting it to `false` first switches bunching to the live feed, then closes the active run. The persisted setting is initialized to `false` by Flyway and survives an ingestion restart. The page uses the equivalent start/close run endpoints so it can display the run ID and alert list.

The page starts a fictional, deterministic route-190 replay and lists simulation-only
bunching alerts. Since the bunching consumer runs independently, after the replay ends
the page keeps checking until the alert arrives and then shows the persisted, simulation-only
recommendation when it is ready; **Return to live** remains available while it waits. Enabling demo
support requires `OCC_DISRUPTION_INTERNAL_URL` and `OCC_PLANNING_INTERNAL_URL`; live DataMall polling remains enabled.
When no demo run is active, the same page also shows the currently open live bunching
detections through ingestion's same-origin `GET /api/v1/live-bunching-alerts` proxy.
The browser does not call bunching directly; the proxy obtains the immutable opening
`alert_event` for each active live episode from bunching.

## Run only ingestion

From the repository root:

```powershell
Set-Location services/ingestion
..\..\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

```bash
cd services/ingestion
../../mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

A global Maven installation is optional; the wrapper downloads Maven on first use.

The `local` profile imports `../../.env`, the repository's single local configuration file. Create it from the root `.env.example` if needed. Relevant values are:

```dotenv
OCC_INGESTION_DB_PASSWORD=your-database-password
DATAMALL_API_KEY=your-datamall-key
```

Use unquoted values, without `export`. The host-facing local URL is `OCC_INGESTION_DB_PUBLIC_URL`; Compose uses `OCC_INGESTION_DB_INTERNAL_URL`. The runtime username and password must match the account provisioned by `infra/postgres/bootstrap.sql`. On a Compose startup that recreates the bootstrap job, it reconciles existing runtime and migrator role passwords with the configured environment values before Flyway or the services start; updating a local role password therefore requires the matching root `.env` value. Schema changes run through the `ingestion-migrate` Compose job using `OCC_INGESTION_DB_MIGRATOR_USERNAME` and `OCC_INGESTION_DB_MIGRATOR_PASSWORD`; the application does not run Flyway at startup.

PostgreSQL must be running before the app starts. To start the shared local database, run this from the repository root:

```powershell
docker compose --env-file .env up -d --build
```

```bash
docker compose --env-file .env up -d --build
```

This starts the shared PostgreSQL service and Phase 1 application stack. See the [local stack setup](../../docs/LOCAL-DEVELOPMENT.md).

## IDE and deployment

Import `pom.xml` as a Maven project. Run `com.csd.occ.ingestion.IngestionApplication` with the `local` profile and working directory `services/ingestion`.

Without the `local` profile, the app does not read the root `.env`; supply deployment configuration through environment variables. Custom properties use `occ.datamall.*`. The DataMall API key remains supplied through `DATAMALL_API_KEY`.

```powershell
.\mvnw.cmd -pl services/ingestion verify
docker compose --env-file .env build ingestion
```

```bash
./mvnw -pl services/ingestion verify
docker compose --env-file .env build ingestion
```

Compose builds the service with the shared root `infra/java.Dockerfile`. Runtime
database and API credentials are supplied through deployment configuration.

## Startup behavior and API

Compose runs the `ingestion-migrate` Flyway job before starting the service; Hibernate validates entity mappings at startup. The service then imports versioned DataMall bus stops, services and route topology. It creates only missing polling targets for `occ.ingestion.monitored-services` (190 and 972 by default). `BusArrivalSchedule` polls each enabled stop every minute. Each successful poll keeps the original response in `raw_observation`, writes one normalized row per service and NextBus position to `bus_arrival_observation`, and updates `feed_status`. Retention removes raw and normalized observations after `occ.ingestion.retention` (12 hours by default), bounds history reads to that same window, and only removes superseded topology after all retained observations stop referencing it. Increasing the window cannot restore already purged rows.

If the active reference topology is absent, the scheduler first retries the reference import and target seeding. If that import fails, it skips the poll cycle without sending per-stop arrival requests; the terminal log records the single import failure.

The arrival poller is enabled by `OCC_INGESTION_BUS_ARRIVAL_ENABLED` (default `true`);
set it to `false` in an isolated test stack to prevent calls to DataMall. When enabled,
its schedule is externally configurable: `OCC_INGESTION_BUS_ARRIVAL_POLL_INTERVAL`
and `OCC_INGESTION_BUS_ARRIVAL_INITIAL_DELAY` default to `60s`; retention cleanup uses
`OCC_INGESTION_RETENTION_INTERVAL=1h` after `OCC_INGESTION_RETENTION_INITIAL_DELAY=5m`.
Poll intervals are validated from 1 second to 1 hour and retention intervals from
1 minute to 24 hours. DataMall calls use a 5-second connect timeout and 20-second
response timeout by default (`OCC_DATAMALL_CONNECT_TIMEOUT` and
`OCC_DATAMALL_RESPONSE_TIMEOUT`); accepted ranges are 100ms–30s and 100ms–2m.
Each decoded response is capped at 5,000,000 bytes by default through
`OCC_DATAMALL_MAX_RESPONSE_BYTES` (accepted range 1,024–20,000,000 bytes).
Oversized responses fail before parsing or persistence; a later incident-page
failure leaves the accumulated snapshot marked incomplete.
LTA Traffic Incidents are polled by a separate scheduler every two minutes by
default, matching the provider's published update frequency. Control it with
`OCC_INGESTION_TRAFFIC_INCIDENT_ENABLED`,
`OCC_INGESTION_TRAFFIC_INCIDENT_POLL_INTERVAL` (minimum `2m`), and
`OCC_INGESTION_TRAFFIC_INCIDENT_INITIAL_DELAY`. The adapter follows the current
DataMall response limit with `$skip` pagination: `OCC_DATAMALL_TRAFFIC_INCIDENT_PAGE_SIZE`
defaults to `500` and `OCC_DATAMALL_TRAFFIC_INCIDENT_MAX_PAGES` defaults to `10`.
If every configured page is full, or a response exceeds the configured page
size, the poll is marked partial so Disruption cannot clear active
incidents from a truncated snapshot. The raw observation preserves every
successful page response; normalized incident rows reference that observation.
The DataMall contract has no incident ID, so the stable source key is derived
from incident type and coordinates rounded to the database's seven decimal
places. A duplicate type/location with conflicting facts also makes the poll
partial. The normalized `observedAt` is null because the provider does not
return an observation timestamp; `fetchedAt` records when OCC received the
snapshot. These pagination limits follow [LTA DataMall API User Guide v6.10,
section 2.18](https://datamall.lta.gov.sg/content/dam/datamall/datasets/LTA_DataMall_API_User_Guide.pdf?ref=public_apis),
which documents a 500-record response limit and `$skip` paging; update the
settings if LTA changes that provider limit.
All values are inventoried in the repository-root `.env.example` and passed to Compose
through the ingestion service environment.

### Polling coverage and bus-bunching coverage

Ingestion, rather than the bus-bunching detector, decides which stops are covered.
The setting is `occ.ingestion.monitored-services`, configured locally through the
`OCC_INGESTION_MONITORED_SERVICES` environment variable. Its default is `190,972`:

```dotenv
# Comma-separated DataMall service IDs. Ingestion polls every imported route stop
# served by at least one configured service; bunching consumes every resulting snapshot.
OCC_INGESTION_MONITORED_SERVICES=190,972
```

At startup, ingestion imports the reference topology and creates missing enabled
`ingestion.poll_target` rows for the distinct stops on those services. The scheduler
then polls each enabled target once per minute. The bunching service consumes all
new normalized snapshots from that target set through ingestion's internal API; it
does not maintain a separate stop or service allow-list.

To broaden coverage, add service IDs to the comma-separated variable and restart
ingestion. Target seeding is additive: it creates missing targets but deliberately
does not disable or remove targets previously created for a service that is later
removed from the setting. Disable or remove those `ingestion.poll_target` rows
through an explicit operational change when reducing coverage.

With the service running in Compose, browse the interactive API at `http://localhost:<OCC_INGESTION_PORT>/swagger-ui.html` (default host port `6767`). These read-only endpoints return paginated envelopes except feed status and reference topology:

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/arrivals?stopCode=&serviceId=&page=&size=` | Latest complete per-stop arrival snapshot |
| `GET /api/v1/arrivals/history?stopCode=&serviceId=&from=&to=&page=&size=` | Retained normalized observation history |
| `GET /api/v1/feed-status` | Poll freshness and failures by stop |
| `GET /api/v1/poll-runs?page=&size=` | Poll audit runs |
| `GET /api/v1/reference-topology` | Active static reference-data version |
| `GET /api/v1/bus-routes?page=&size=` | Bus services and operators in the active reference topology |
| `GET /api/v1/bus-routes/{serviceId}` | Ordered bus stops for every direction of a bus service |
| `GET /api/v1/bus-stops?page=&size=` | Bus stops in the active reference topology |
| `GET /api/v1/bus-stops/{stopCode}` | One bus stop by stop code |

The machine-readable OpenAPI document is at `/v3/api-docs`.

### Root-cause evidence contract

WQAP-3 retrieves simulation context through
`GET /internal/v1/demo-runs/{demoRunId}/root-cause-context` with the confirmed
event's `at`, `serviceId`, `stopCode`, `directionId`, and two-to-ten
`arrivalObservationIds` query parameters. The endpoint verifies that each observation
matches the selected simulation run, event location/service, and time window, then
returns stable evidence IDs, provenance, timestamps, vehicle/load/location fields, matching simulation incidents, and an
explicit `missingFields` list. It never returns `OBSERVATION_ONLY` records to a
simulation diagnosis. Incident `observedAt` is retained when supplied; the fictional
replay uses its actual simulation timestamp rather than leaving the field absent.

To export the runtime-generated contract, start the local Compose stack and set a
short-lived ADMIN or CONTROLLER access token in the current shell only. Do not put
the token in `.env`, a command history, or a committed file. Then run from the repo
root:

```powershell
$env:OCC_OPENAPI_EXPORT_TOKEN = '<short-lived access token>'
.\mvnw.cmd -pl services/ingestion -Popenapi org.springdoc:springdoc-openapi-maven-plugin:1.5:generate "-Dopenapi.apiDocsUrl=http://localhost:6767/v3/api-docs"
Remove-Item Env:OCC_OPENAPI_EXPORT_TOKEN
```

```bash
export OCC_OPENAPI_EXPORT_TOKEN='<short-lived access token>'
./mvnw -pl services/ingestion -Popenapi org.springdoc:springdoc-openapi-maven-plugin:1.5:generate -Dopenapi.apiDocsUrl=http://localhost:6767/v3/api-docs
unset OCC_OPENAPI_EXPORT_TOKEN
```

This writes `services/ingestion/openapi/ingestion.json`. The export uses the same
running application endpoint as Swagger UI, so it includes the actual controllers,
DTOs, annotations and active Spring configuration.

A missing or invalid DataMall key can produce request errors even if the application starts. Stop the app with Ctrl+C.

Tests use fake DataMall responses and synthetic configuration, never the root `.env`. They do not prove live DataMall availability.

## Database reduction and retention

Operational history uses `OCC_INGESTION_RETENTION` (default 12h), with existing
retention interval and initial-delay environment bindings. Reads enforce the
cutoff before cleanup sweeps; arrival history is measured by fetch time, never ETA.
Closed/failed demos expire after their lifecycle cutoff; an expired run returns
404 even before physical cleanup. Selected runs, deferred work, and undrained
bunching outboxes protect their simulation input. If protection cannot be read,
cleanup fails visibly. Children are deleted before raw observations, poll runs,
assignments, and runs. Active topology is always retained. Retired versions are
pruned only outside the window and without arrival references, under the same
transaction advisory lock as topology import. Increasing retention cannot restore
purged data. AI evidence is snapshotted in planning before operational input expires.

V015 retires ingestion's obsolete cross-schema bunching views; V016 removes child
import timestamps; V017 records topology retirement. `current_bus_arrival` stays.
Existing deployments must use the administrator-only reserved-schema procedure
in [the reduction verification document](../../docs/plans/database-reduction-verification.md).
