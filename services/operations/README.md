# OCC Operations Service: draft demo fleet

This initial Operations slice stores **prototype-owned** draft runs, vehicles,
drivers, and assignments. It reads Ingestion's imported route and stop reference
topology to validate service/direction and draft positions, even when no live
buses are running. It does not read or modify DataMall arrival telemetry. A draft
cannot trigger bunching planning or send a directive. Live observation capture,
driven-path validation, simulation execution, and driver delivery remain to be built.

## Run

The service uses Java 25, PostgreSQL and Flyway. Run Ingestion first and import
its route reference topology. Set the root `.env` values for the Operations
database. Login is disabled by default (`OCC_COGNITO_ENABLED=false`).
Cognito settings are optional until authentication is enabled. From this directory:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

For Compose, use `--profile demo` with `infra/local/compose.yaml`. The service
listens on host port `6768` by default. `/v3/api-docs` and `/swagger-ui.html`
describe the API when enabled. With `OCC_COGNITO_ENABLED=false`, draft runs and
vehicle placement require no login; creators are recorded as `prototype-local`.
Set `OCC_COGNITO_ENABLED=true`, `OCC_COGNITO_ISSUER_URI`, and
`OCC_COGNITO_APP_CLIENT_ID` to restore Cognito authentication. In that mode,
business API requests need a Cognito bearer token. `ADMIN` and `CONTROLLER`
groups can create/read draft runs and vehicles. Tokens must be Cognito access
tokens for the configured app client. A `DRIVER` token cannot access
these endpoints. The authenticated token subject is recorded as run creator.

## API

| Method/path | Purpose |
| --- | --- |
| `POST /api/v1/demo-runs` | Create a draft for one service and direction |
| `GET /api/v1/demo-runs/{runId}` | Read a draft |
| `GET /api/v1/demo-runs/{runId}/route` | Read ordered stops for the selected direction |
| `POST /api/v1/demo-runs/{runId}/vehicles` | Create a stable demo vehicle, demo driver and assignment together |
| `GET /api/v1/demo-runs/{runId}/vehicles` | Paginated assigned vehicles |
| `GET /api/v1/demo-runs/{runId}/vehicles/{vehicleId}` | One assignment |

For example, `POST /api/v1/demo-runs` accepts:

```json
{"serviceId":"190","directionId":1}
```

Then `POST /api/v1/demo-runs/{runId}/vehicles` accepts:

```json
{"displayLabel":"Bus B","syntheticPlate":"DEMO-190-B","latitude":1.3786545,
 "longitude":103.7584354,"driverDisplayName":"Demo Driver 02"}
```

The response carries immutable IDs for the vehicle, driver and assignment, and
`classification: SIMULATION`. Synthetic plate labels require a `DEMO-` prefix.
The vehicle list uses `OCC_DEMO_FLEET_DEFAULT_PAGE_SIZE` when `size` is omitted
and rejects values above `OCC_DEMO_FLEET_MAX_PAGE_SIZE`.
Coordinates are stored as **draft positions**. The selected service and direction
must exist in Ingestion's active reference topology; a position must be within
`OCC_DEMO_ROUTE_MAX_STOP_DISTANCE_METERS` (default 250 m) of one of its stops.
This stop proximity check is an approximation, not validation against the driven
road path. It is rechecked against the currently active topology when a vehicle
is added. The API does not present a draft as an active simulated trip. A duplicate
label or plate within a run returns `409` and cannot create another assignment.
If the reference API is unavailable, creation returns `503` rather than accepting
an unverified position. `OCC_OPERATIONS_INGESTION_TIMEOUT` controls its timeout.

## View the draft map

Open `http://localhost:6768/demo-map.html`. The geographic basemap and blue live
DataMall observations load immediately without a token or saved run. Filter by
service, or leave the filter blank for all monitored services. The live layer
reads stored Ingestion observations every `OCC_MAP_REFRESH_INTERVAL` (default
60 seconds); it does not initiate extra DataMall polls. Stale observations are
hidden, and a failed refresh clears live markers. Co-located observations share
a popup; this does not infer vehicle identity or deduplicate across stops.

For the purple demo overlay, create a service/direction run and place labelled demo
buses at route stops. You can also load a run created through Swagger UI by ID.
The run ID is generated automatically when you create a scenario; it is needed
only to reopen a saved scenario later.
The token stays in page memory and is cleared on reload. The page loads the
draft route endpoint and paginated vehicle list. Demo runs remain independent
of live observations. Dashed route lines connect stop coordinates rather than
showing a verified driven path. The Leaflet 1.9.4 library is vendored locally;
the browser fetches visible map tiles from `OCC_MAP_TILE_URL`, with attribution
from `OCC_MAP_ATTRIBUTION_TEXT` and `OCC_MAP_ATTRIBUTION_URL`. The default is
OpenStreetMap standard tiles; internet access is needed for the background.
Follow the provider's usage policy and configure a suitable provider for larger
deployments: https://operations.osmfoundation.org/policies/tiles/.

The read-only endpoints `GET /api/v1/map/settings` and
`GET /api/v1/map/observed-buses?serviceId=&page=&size=` are anonymous, matching
Ingestion's public observation API. Demo mutations require roles only when `OCC_COGNITO_ENABLED=true`.

## Verification

Run `.\mvnw.cmd verify`. Integration tests use disposable PostgreSQL through
Testcontainers and apply the Operations migrations. No real Cognito or DataMall
account is used in tests.

## Inferred live bus tracks

The live map defaults to `/api/v1/map/inferred-tracks` (anonymous, paginated).
Operations periodically reads Ingestion's existing feed; no extra DataMall calls.
Persistent `inferred_vehicle_track` and `inferred_observation_link` records retain
temporary UUID identities and source evidence. Labels are not real plates.

Coordinates are projected onto imported stop-to-stop route geometry. A known
direction (provided or inferred from unique stop membership) and a unique stop visit
are required. Compatible cross-stop observations
are clustered; two arrival slots at one stop are never clustered together.
Movement, timing and same-stop ETA continuity constrain identity matches. Competing
identities or candidates mark tracks AMBIGUOUS. Initial identities are PROVISIONAL,
then ACTIVE after evidence from a later source time. Confidence is a heuristic
score, not a probability. Tracks cannot receive directives or demo assignments.
Route intersections, ambiguous stop membership and off-route positions remain unresolved.
Each track pins a route fingerprint; changed geometry expires the old hypothesis.

The raw-observation toggle allows source inspection. Counts are not a fleet census:
the approximation can split one real bus into several hypotheses or link compatible
buses incorrectly. IDs survive service restarts, while source links make decisions
inspectable. Stale tracks are hidden, unmatched tracks expire, and old rows are
purged after retention. PostgreSQL serializes projection writers. Demo buses,
drivers and assignments remain separate.

Configuration (also listed in root `.env.example`):

| Variable | Default | Purpose |
| --- | --- | --- |
| `OCC_TRACK_INFERENCE_ENABLED` | `true` | Enable persistent track inference. |
| `OCC_TRACK_INFERENCE_INTERVAL` | `60s` | Delay between projection refreshes; no extra DataMall calls. |
| `OCC_TRACK_INFERENCE_INITIAL_DELAY` | `5s` | Startup delay. |
| `OCC_TRACK_INFERENCE_STALE_AFTER` | `180s` | Hide tracks older than this source age. |
| `OCC_TRACK_INFERENCE_EXPIRY` | `5m` | Expire unmatched identities. |
| `OCC_TRACK_INFERENCE_RETENTION` | `24h` | Retention for tracks and observation links. |
| `OCC_TRACK_INFERENCE_CLUSTER_TIME_WINDOW` | `30s` | Maximum source-time difference for cross-stop clusters. |
| `OCC_TRACK_INFERENCE_CLUSTER_METERS` | `60` | Maximum cluster position/progress separation in meters. |
| `OCC_TRACK_INFERENCE_MAX_ROUTE_OFFSET_METERS` | `400` | Maximum offset from approximate route in meters. |
| `OCC_TRACK_INFERENCE_MAX_SPEED_METERS_PER_SECOND` | `25` | Maximum physically plausible speed. |
| `OCC_TRACK_INFERENCE_BACKWARD_TOLERANCE_METERS` | `80` | Allowed backwards progress noise. |
| `OCC_TRACK_INFERENCE_AMBIGUITY_MARGIN_METERS` | `20` | Score gap for competing matches. |
| `OCC_TRACK_INFERENCE_ETA_TOLERANCE` | `90s` | Maximum same-stop ETA change. |
| `OCC_TRACK_INFERENCE_PAGE_SIZE` | `200` | Upstream observation page size, 1 to 200. |
| `OCC_TRACK_INFERENCE_MAX_PAGES` | `20` | Abort above this limit to avoid partial evidence. |
