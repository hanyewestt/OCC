# OCC Ingestion Service

Polls DataMall bus arrivals and traffic incidents and records observations for analytics. Requires Java 25, PostgreSQL with the `ingestion` schema and `occ_ingestion` account, and a valid DataMall key.

## Demo replay

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
| `GET /api/v1/route-positions?service=190` | Recent, direction-scoped position estimates for a bus service |

Route positions are temporary evidence-based estimates, not registered vehicle
identities. The service filters missing or zero coordinates and observations
older than `OCC_INGESTION_ROUTE_POSITION_MAX_POLL_AGE_CYCLES` bus-arrival poll
cycles (default `2`), then uses only the newest completed eligible poll cycle.
Other completed cycles inside that same bounded window contribute only a
co-occurrence veto: coordinate identities previously returned together in one
raw stop snapshot cannot later be linked. They never contribute displayed buses,
and an empty newest completed cycle therefore remains empty instead of falling
back to an older sweep.
Within each operator and direction, an exact provider coordinate plus origin,
destination and visit number forms one atomic identity version. Compatible
versions that never occur in the same stop snapshot may be linked when they are
within `OCC_INGESTION_ROUTE_POSITION_IDENTITY_LINK_DISTANCE_M` (default `25` m)
and their ETA profiles, normalized with
`OCC_INGESTION_ROUTE_POSITION_IDENTITY_LINK_PROJECTION_SPEED_MPS` (default
`7.0` m/s), differ by no more than
`OCC_INGESTION_ROUTE_POSITION_IDENTITY_ETA_TOLERANCE` (default `20s`). Versions
returned together in a snapshot are always treated as separate buses. The
estimator also preserves the largest multiplicity reported by any one stop
snapshot, so repeated or co-located buses are not collapsed. `visitOrder` is
only queue order at a stop and is not treated as a vehicle identifier.
Short-range linkage runs only for the comma-separated `service:direction`
allow-list in `OCC_INGESTION_ROUTE_POSITION_IDENTITY_LINK_ROUTES` (default
`190:1,190:2`), which limits the behavior to the route lines validated by the
replay. An empty value disables short-range linkage everywhere.

Some provider coordinates can change while the stops in one service poll cycle
are still being queried. Coordinated refresh-boundary reconciliation is therefore
limited to the comma-separated `service:direction` allow-list in
`OCC_INGESTION_ROUTE_POSITION_COORDINATED_REFRESH_BOUNDARY_ROUTES` (default
`190:1`). It requires an unambiguous coordinated set of matches within
`OCC_INGESTION_ROUTE_POSITION_REFRESH_BOUNDARY_DISTANCE_M` (default `250` m) and
`OCC_INGESTION_ROUTE_POSITION_REFRESH_BOUNDARY_WINDOW` (default `5s`), tolerates
at most `OCC_INGESTION_ROUTE_POSITION_REFRESH_BOUNDARY_ALLOWED_OVERLAP` (default
`250ms`) of timestamp overlap, and caps the ETA-profile residual at
`OCC_INGESTION_ROUTE_POSITION_REFRESH_BOUNDARY_PROFILE_RESIDUAL` (default `75s`).
The fitted ETA-profile slope must also remain between
`OCC_INGESTION_ROUTE_POSITION_REFRESH_BOUNDARY_PROFILE_MIN_SECONDS_PER_M`
(default `0.02`) and
`OCC_INGESTION_ROUTE_POSITION_REFRESH_BOUNDARY_PROFILE_MAX_SECONDS_PER_M`
(default `2.0`) seconds per metre; deployments can tune that plausibility range
without changing code.
An empty allow-list disables this reconciliation. Ambiguous matches and any pair
that co-occurs in a stop snapshot remain separate.

Coordinates are projected onto a line built from the ordered stop coordinates
because the active reference data contains stop points but no road-level route
polyline. The line can cut across road bends, so the displayed distance remains
approximate. For a circular route whose first and last stop repeat, the same
coordinate can project to several route occurrences. With at least
`OCC_INGESTION_ROUTE_POSITION_CIRCULAR_PROJECTION_MIN_STOP_COUNT` distinct stops
(default `3`), the estimator selects an occurrence by comparing provider ETAs
with forward distances in the reference data. That comparison uses
`OCC_INGESTION_ROUTE_POSITION_CIRCULAR_PROJECTION_SPEED_MPS` (default `7.0` m/s)
and admits candidates within
`OCC_INGESTION_ROUTE_POSITION_CLUSTER_TOLERANCE_M` (default `150` m) of the
nearest route segment. With insufficient evidence it retains the nearest
projection, which is also the behavior for non-circular routes.

Circular routes may report separate coordinate versions for the same bus around
the repeated start/end phase. A unique pair can share occurrence-selection
evidence without merging either estimate when it did not co-occur, is within
`OCC_INGESTION_ROUTE_POSITION_CIRCULAR_PHASE_LINK_DISTANCE_M` (default `250` m),
has at most `OCC_INGESTION_ROUTE_POSITION_CIRCULAR_PHASE_MAXIMUM_GAP_M` (default
`2000` m) of route phase separation, and has no more than
`OCC_INGESTION_ROUTE_POSITION_CIRCULAR_PHASE_MAXIMUM_ETA_ERROR` (default `75s`)
of ETA error. Sharing phase evidence changes only occurrence selection; it never
reduces the number of displayed buses. It runs only for the comma-separated
`service:direction` allow-list in
`OCC_INGESTION_ROUTE_POSITION_CIRCULAR_PHASE_LINK_ROUTES` (default `972:1`),
which limits the behavior to the circular route line validated by the replay.
An empty value disables phase evidence sharing everywhere.

The response groups estimates by operator and direction. Each interval uses the
configured half-width `OCC_INGESTION_ROUTE_POSITION_UNCERTAINTY_M` (default
`50` m), matching the blue position rectangle in the route UI. The UI refresh
interval is based on `OCC_INGESTION_BUS_ARRIVAL_POLL_INTERVAL` and clamped to
30–60 seconds. Each estimate also returns its source arrival observation IDs;
these IDs can link saved bunching evidence to an estimate, but they identify
records rather than physical buses. At response level, `sourcePollCycleId`
identifies the selected provider poll cycle and `sourceObservedAt` is the newest
source reading time in that cycle. `computedAt` is deliberately separate: it is
the time the API calculated the response. `maxSourceAgeSeconds` exposes the
same configured source-age limit used by the estimator so clients do not need
to duplicate the cycle threshold. Source fields are `null` when the newest
completed cycle contains no usable readings.

`OCC_INGESTION_ROUTE_POSITION_SPEED_CAP_MPS` remains accepted for deployment
compatibility with the former cross-poll tracker. The current estimator does
not extrapolate a bus between polls, so this legacy setting does not affect its
output.

The machine-readable OpenAPI document is at `/v3/api-docs`.

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
