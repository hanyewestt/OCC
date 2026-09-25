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
database, `OCC_COGNITO_ISSUER_URI`, and
`OCC_COGNITO_APP_CLIENT_ID`. From this directory:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

For Compose, use `--profile demo` with `infra/local/compose.yaml`. The service
listens on host port `6768` by default. `/v3/api-docs` and `/swagger-ui.html`
describe the API when enabled. Business API requests need a Cognito bearer token. `ADMIN` and `CONTROLLER`
groups can create/read draft runs and vehicles. Tokens must be Cognito access
tokens for the configured app client. A `DRIVER` token cannot access
these endpoints. The authenticated token subject is recorded as run creator.

## API

| Method/path | Purpose |
| --- | --- |
| `POST /api/v1/demo-runs` | Create a draft for one service and direction |
| `GET /api/v1/demo-runs/{runId}` | Read a draft |
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

## Verification

Run `.\mvnw.cmd verify`. Integration tests use disposable PostgreSQL through
Testcontainers and apply the Operations migrations. No real Cognito or DataMall
account is used in tests.
