# OCC Ingestion Service

Polls DataMall bus arrivals and traffic incidents and records observations for analytics. Requires Java 25, PostgreSQL with the `ingestion` schema and `occ_ingestion` account, and a valid DataMall key.

## Demo replay

## Additional hazards

Flood, rail and approved roadworks use independent `OCC_INGESTION_FLOOD_*`,
`OCC_INGESTION_RAIL_*` and `OCC_INGESTION_ROADWORKS_*` enablement, interval,
initial-delay and stale-after settings. All default enabled. Shared bounded
concurrency uses Spring's `OCC_INGESTION_SCHEDULER_POOL_SIZE` (8 workers), so a
slow feed does not serialize all independent polls. Shared bounded
pagination uses `OCC_INGESTION_HAZARD_PAGE_SIZE` (500) and
`OCC_INGESTION_HAZARD_MAX_PAGES` (10); date-only/local provider timestamps use
`OCC_INGESTION_HAZARD_SOURCE_TIMEZONE` (Asia/Singapore). Flood coverage is a
broadcast radius, not flooded-road geometry; approved work does not imply closure.
Migration V022 stores flood, rail and roadworks observations in a separate
`ingestion.hazard_observation` table and moves existing rows out of the traffic
table. The legacy traffic table and its coordinate constraints are restored so a
rollback to the preceding application can read traffic observations safely. Keep
the migrated database when rolling back; the preceding application ignores the
additional-hazard table, and a roll-forward resumes reading it.
`/internal/v1/hazard-polls` exposes
completed additional-feed polls to the Disruption peer, including incomplete polls.
Original page bodies are stored as JSON strings in a JSON array, preserving even
malformed provider text without logging credentials. The checksum covers the JSON
array encoded with the application mapper: decode/re-encode the page strings
before verification because PostgreSQL JSONB normalizes outer whitespace.
Retention covers all kinds.
The metrics `occ.ingestion.hazard.latest_poll_outcome` and
`occ.ingestion.hazard.latest_poll_success_age_seconds` use the bounded `feed`
label (`FLOOD`, `RAIL_DISRUPTION`, `ROADWORKS`); outcome values are 1 for
success, 0.5 for partial, 0 for failed and -1 before the first completed poll.
No bus, account, incident or run identifiers are metric labels.

An optional `OCC_INGESTION_STATION_REFERENCE_LOCATION` imports a separately
versioned station JSON document on startup, bounded by
`OCC_INGESTION_STATION_REFERENCE_MAX_BYTES`. Blank skips import. The document is
`{"stations":[{"code":"NE1","name":"HarbourFront","latitude":1.265,"longitude":103.821}]}`.
Use provider reference data and make the configured file/resource available in
the container. Station coordinates may both be null; unresolved stations stay
unresolved. Each imported station retains the source document checksum.

### Demo identity

Set `OCC_DEMO_CAPTAIN_USER_ID` to the stable Supabase UUID of `captain1@occ.com`
to bind `demo-190-b` to that account when preparing a new run. Blank leaves buses
unassigned and does not prevent replay. Assignments are immutable for a run;
configuration changes apply to the next run. Scenario bus IDs equal the existing
stable trip IDs; always pair them with the run ID. Email and captain codes never
authorize access. `/internal/v1/ingestion/demo-fleet?demoRunId=<uuid>&page=0&size=20`
accepts Planning/Disruption peer capabilities and returns current assignments and
simulation progress. Closed/preparing runs expose no current buses. Speed context
is a synthetic fixture (30 km/h, permitted 5 km/h reduction for 300 seconds),
not vehicle telemetry. Unresolved route occurrences have null progress.

The React frontend currently provides login and role-access checks only. Replay remains available through the protected APIs below for ADMIN and CONTROLLER accounts. Its persisted runtime state starts disabled, so the replay scheduler does no work and bunching remains live until a run starts. The mode can be toggled while ingestion is running; no restart is needed:

```http
GET /api/v1/demo-mode
PUT /api/v1/demo-mode
Content-Type: application/json

{"enabled":true}
```

Setting `enabled` to `true` starts a replay if none is active, or resumes the currently active run. Setting it to `false` first switches bunching to the live feed, then closes the active run. The persisted setting is initialized to `false` by Flyway and survives an ingestion restart. The start/close run endpoints provide equivalent control with explicit run IDs.

Replay uses a fictional, deterministic multi-route scenario. Each run produces one
two-bus bunching event for service 190 and one three-bus bunching event for service
972, with stable vehicle registrations shared by the Planning fixtures. The replay
produces simulation-only bunching alerts and recommendations. The bunching consumer runs independently, so
results can arrive after replay completes. Replay support requires
`OCC_DISRUPTION_INTERNAL_URL` and `OCC_PLANNING_INTERNAL_URL`; live DataMall polling remains enabled.
`GET /api/v1/live-bunching-alerts` retrieves the immutable opening `alert_event`
for each active live episode through ingestion's proxy to disruption.

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

The arrival poller is enabled by default (`occ.ingestion.schedule.bus-arrival-enabled=true`);
set `OCC_INGESTION_BUS_ARRIVAL_ENABLED=false` in an isolated test stack to prevent calls to DataMall.
Operational timings, retry bounds, and pagination limits are configured with safe canonical defaults
in `application.yml`: the bus arrival poller defaults to a 60-second interval with a 60-second initial delay,
while retention cleanup runs every hour after an initial 5-minute delay. DataMall HTTP calls use a default
5-second connect timeout and 20-second response timeout, with a 5,000,000-byte response limit.
LTA Traffic Incidents are polled every two minutes by default, matching the provider's update frequency,
with 500-record `$skip` pages (up to 10 pages). All operational parameters can be tuned via standard
Spring Boot relaxed binding or the retained legacy environment aliases when necessary.
In Compose, inject optional overrides into the service with an additional Compose file;
the base stack no longer forwards tuning values from the root `.env`. Deployment credentials, ports,
and monitored services are inventoried in the repository-root `.env.example`.

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
| `GET /api/v1/arrivals?stopCode=&serviceId=&stopName=&sortBy=&sortDirection=&page=&size=` | Latest complete per-stop arrival snapshot; stop-name search and stop-name/arrival-time sorting apply across the matching result set before pagination |
| `GET /api/v1/arrivals/history?stopCode=&serviceId=&from=&to=&page=&size=` | Retained normalized observation history |
| `GET /api/v1/feed-status` | Poll freshness and failures by stop |
| `GET /api/v1/poll-runs?page=&size=` | Poll audit runs |
| `GET /api/v1/reference-topology` | Active static reference-data version |
| `GET /api/v1/bus-routes?page=&size=` | Bus services and operators in the active reference topology |
| `GET /api/v1/bus-routes/{serviceId}` | Ordered bus stops for every direction of a bus service |
| `GET /api/v1/bus-stops?page=&size=` | Bus stops in the active reference topology |
| `GET /api/v1/bus-stops/{stopCode}` | One bus stop by stop code |
| `GET /api/v1/route-positions?service=190` | Recent, direction-scoped position estimates for a bus service |

Route positions are temporary evidence-based estimates, not registered vehicle
identities. The service filters missing or zero coordinates and observations
older than two bus-arrival poll cycles, then uses only the newest completed
eligible poll cycle. Other completed cycles inside that bounded window contribute
only a co-occurrence veto: coordinate identities previously returned together in
one raw stop snapshot cannot later be linked. They never contribute displayed
buses, and an empty newest completed cycle remains empty instead of falling back
to an older sweep.

Within each operator and direction, an exact provider coordinate plus origin,
destination and visit number forms one atomic identity version. Compatible
versions that never occur in the same stop snapshot may be linked when they are
within 25 m and their ETA profiles, normalized at 7 m/s, differ by no more than
20 seconds. Versions returned together in a snapshot are always treated as
separate buses. The estimator also preserves the largest multiplicity reported
by any one stop snapshot, so repeated or co-located buses are not collapsed.
`visitOrder` is only queue order at a stop and is not treated as a vehicle
identifier. This short-range linkage is limited to the replay-validated route
lines 190 direction 1 and 190 direction 2.

Some provider coordinates can change while the stops in one service poll cycle
are still being queried. For 190 direction 1, coordinated refresh-boundary
reconciliation requires an unambiguous coordinated set of matches within 250 m
and five seconds. It tolerates no more than 250 ms of timestamp overlap, caps the
ETA-profile residual at 75 seconds, and accepts fitted ETA-profile slopes only
from 0.02 through 2.0 seconds per metre. Ambiguous matches and any pair that
co-occurs in a stop snapshot remain separate.

Coordinates are projected onto a line built from the ordered stop coordinates
because the active reference data contains stop points but no road-level route
polyline. The line can cut across road bends, so the displayed distance remains
approximate. For a circular route whose first and last stop repeat, the same
coordinate can project to several route occurrences. With at least three
distinct stops, the estimator selects an occurrence by comparing provider ETAs
with forward distances using a nominal speed of 7 m/s. It admits occurrence
candidates within 150 m of the nearest route segment. With insufficient evidence
it retains the nearest projection, which is also the behavior for non-circular
routes.

Circular routes may report separate coordinate versions for the same bus around
the repeated start/end phase. On 972 direction 1, a unique pair can share
occurrence-selection evidence without merging either estimate when it did not
co-occur, is within 250 m, has at most 2,000 m of route-phase separation, and has
no more than 75 seconds of ETA error. Sharing phase evidence changes only
occurrence selection; it never reduces the number of displayed buses.

The response groups estimates by operator and direction. Each interval uses a
50 m half-width, matching the blue position rectangle in the route UI. The UI
refresh is clamped to 30–60 seconds. Each estimate also returns audit source
arrival observation IDs and the position-evidence observation IDs that justify
its displayed location. These IDs can link saved bunching evidence to an
estimate, but they identify records rather than physical buses. At response
level, `sourcePollCycleId` identifies the selected provider poll cycle and
`sourceObservedAt` is the newest source reading time in that cycle. `computedAt`
is deliberately separate: it is the time the API calculated the response.
`maxSourceAgeSeconds` exposes the estimator's source-age limit so clients do not
duplicate it. Source fields are `null` when the newest completed cycle contains
no usable readings. The current estimator does not extrapolate buses between
polls.

The machine-readable OpenAPI document is at `/v3/api-docs`.

Current arrivals can be filtered by a trimmed, case-insensitive literal substring
of the version-matched stop name through `stopName`. It is combined with the exact
`stopCode` and `serviceId` filters before pagination. `sortBy` accepts `arrival`
(default) or `stopName`; `sortDirection` accepts `asc` (default) or `desc`.
Missing arrival times and unresolved stop names remain last in either direction.
Equal sort values use service, visit order and observation ID as deterministic
tie-breakers. Page size remains bounded to 200.

### Bus captain pacing (WQAP-68)

Ingestion recalculates each bus's gap to the bus ahead and behind every
`OCC_INGESTION_PACING_INTERVAL` (default `10s`) on a dedicated thread, so slow
DataMall polls cannot delay it, and pushes the snapshot to every subscriber.
Unlike the other APIs, these two are readable by ADMIN, CONTROLLER and CAPTAIN:

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/pacing-snapshots/stream?serviceId=&vehicleReference=` | Server-Sent Events: the current snapshot immediately, then one `pacing` event per cycle |
| `GET /api/v1/pacing-snapshots/latest?serviceId=&vehicleReference=` | The latest snapshot once; `503` before the first cycle |

Each item gives `ahead` and `behind` (`null` when that neighbour is not in the
same prediction set) with `headwaySeconds` and a `TOO_CLOSE`, `ON_TARGET` or
`TOO_FAR` state against `OCC_INGESTION_PACING_TARGET_HEADWAY` (default `180s`,
twice the 90s bunching threshold). A gap at or below the target times
`OCC_INGESTION_PACING_TOO_CLOSE_RATIO` (`0.5`) is too close; at or above
`OCC_INGESTION_PACING_TOO_FAR_RATIO` (`1.5`) is too far. Prediction sets fetched
`OCC_INGESTION_PACING_STALE_AFTER` (`180s`) or longer ago are not paced.

Simulation buses keep their fictional registration as `vehicleReference`. Bus
Arrival does not identify vehicles, so a live bus is named by queue position
(`LIVE-<service>-D<direction>-<stop>-<position>`), stable only within one
snapshot. Streams close after `OCC_INGESTION_PACING_STREAM_TIMEOUT` (`30m`) and
clients reconnect; at most `OCC_INGESTION_PACING_MAX_SUBSCRIBERS` (`200`) are
connected at once. If `calculatedAt` stops advancing, treat the data as stale.

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

Alternatively, without a live token, run the opt-in export test, which starts the
application against a disposable PostgreSQL container (Docker required) and writes
the same file using a synthetic ADMIN test token:

```shell
./mvnw -pl services/ingestion verify "-Docc.exportOpenapi=true"
```

A missing or invalid DataMall key can produce request errors even if the application starts. Stop the app with Ctrl+C.

Tests use fake DataMall responses and synthetic configuration, never the root `.env`. They do not prove live DataMall availability.

## Database reduction and retention

Demo-run alert responses include `evidence`, the saved simulation detection
evidence JSON received from Disruption. The controller UI uses its snapshot to
display Bus 1/2/3 arrival slots at detection time, alongside the existing stop
name and direction. Live alerts already expose the same evidence format. Missing
prediction slots remain unavailable; no live records are mixed into simulation
evidence. This additive response field requires no database migration.

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

### Live and simulation Planning evidence

The Planning peer can read `/internal/v1/ingestion/planning-context` for live
observations or `/internal/v1/ingestion/demo-runs/{demoRunId}/planning-context`
for simulation observations. Both require the exact confirming arrival observation
IDs and reject mixed scope, route or direction evidence. Live evidence must remain
inside `OCC_INGESTION_RETENTION`; no current-provider request or synthetic fixture is
substituted when retained observations are missing. Public JWTs and the Disruption
peer token cannot read the live Planning evidence endpoint.
