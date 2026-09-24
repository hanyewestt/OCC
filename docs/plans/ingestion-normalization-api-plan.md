# Ingestion normalization and read API plan

## Outcome

Turn the ingestion service from a raw Bus Arrival collector into a complete observation pipeline:

```text
DataMall reference import -> versioned stop/service/route topology
DataMall Bus Arrival poll  -> poll audit + raw JSON + normalized arrivals
normalized history         -> current-state view + paginated read APIs
24-hour telemetry          -> scheduled retention cleanup
```

This phase remains observation-only. Live DataMall data must not trigger planning or alter simulations.

## Current state

Implemented:

- `BusRoutes` is scanned at startup to seed stop targets for services 190 and 972.
- `/v3/BusArrival?BusStopCode=...` is polled once per minute for each enabled target.
- `poll_run` records request metadata and outcome.
- `raw_observation` stores the complete response JSON.

Missing:

- Static reference imports into `reference_dataset_version`, `bus_stop`, `bus_service`, and `bus_route_stop`.
- Parsing and persistence into `bus_arrival_observation`.
- Updates to `feed_status`.
- A current-state view.
- Read-only HTTP endpoints and OpenAPI.
- The 24-hour retention job.
- Safe handling of a second poll for the same target in the same minute.

## Schema decisions

Do not edit migrations V001-V004. They may already be applied and Flyway validates their checksums. Add migrations sequentially.

### V005: complete the normalized arrival shape

Add nullable source fields that exist in Bus Arrival v3 but are missing from `bus_arrival_observation`:

- `origin_code varchar(12)`
- `destination_code varchar(12)`
- `monitored boolean`
- `vehicle_type varchar(10)`

Keep the existing names:

- `service_id` represents DataMall `ServiceNo`.
- `visit_order` represents response position: `NextBus` = 1, `NextBus2` = 2, `NextBus3` = 3.
- `direction_id` remains nullable because Bus Arrival does not directly supply a reliable route direction.

Do not add a vehicle identifier: Bus Arrival v3 does not supply a stable physical bus/fleet ID.

Keep `reference_version_id` and its foreign keys mandatory. The reference importer must activate a valid topology version before dynamic polling begins. This avoids normalized rows that cannot be interpreted against a known route/stop dataset.

Add or confirm indexes supporting:

```sql
(stop_code, fetched_at desc)
(service_id, direction_id, fetched_at desc)
(estimated_arrival_at)
(raw_observation_id)
(poll_run_id)
```

### V006: current state view

Create `ingestion.current_bus_arrival` as a normal PostgreSQL view. First select the newest successful `poll_run` for each stop, then return only normalized rows belonging to that poll. Do not independently select the newest row for each arrival position: if the latest response has only one approaching bus, doing so would incorrectly retain `NextBus2` and `NextBus3` rows from an older snapshot. Do not persist a `current_state` boolean; it can drift and creates two competing sources of truth.

The view should expose at least:

```text
id, raw_observation_id, poll_run_id, reference_version_id
stop_code, service_id, operator, direction_id, visit_order
origin_code, destination_code, estimated_arrival_at
latitude, longitude, monitored, load_category, feature, vehicle_type
observed_at, fetched_at, ingested_at
age_seconds
```

Failed polls do not replace the latest successful snapshot. Do not hide stale rows in SQL while they remain inside the 24-hour retention window. Return age/freshness information so the API can state that the last successful value is stale. A successful response containing no services is a valid empty snapshot and must not resurrect predictions from an older poll.

### Idempotency correction

Keep `uq_poll_run_idempotency_key`, but change application behavior:

- Generate the minute-bucket key before requesting DataMall.
- Attempt to claim the poll once.
- If the key already exists, skip that target and record no second request.
- Prevent overlapping scheduled executions with an in-process guard; the database constraint remains the final protection across processes.

Do not remove the uniqueness constraint.

## Are the currently unused tables needed?

| Table | Decision | Reason |
| --- | --- | --- |
| `poll_target` | Keep and use | Runtime configuration for enabled stops and intervals. Replace hard-coded route-specific seeding later with configured profiles. |
| `poll_run` | Keep and use | Request audit, idempotency, latency/outcome reporting, and failure diagnosis. |
| `raw_observation` | Keep and use | Exact source payload, provenance, replay/debugging, and protection against parser defects. |
| `bus_arrival_observation` | Keep and implement | Typed/indexed data for current and historical arrival APIs. |
| `feed_status` | Keep and implement | Cheap health/freshness endpoint without scanning every historical poll. |
| `reference_dataset_version` | Keep and implement | Makes topology imports atomic, traceable, and safely replaceable. |
| `bus_stop` | Keep and implement | Stop metadata and PostGIS location; required by the normalized observation foreign key. |
| `bus_service` | Keep and implement | Versioned service/operator identity. |
| `bus_route_stop` | Keep and implement | Ordered route membership and direction mapping for services 190/972 and future profiles. |

No additional `current_state` table is needed initially. Use the view. If measured query latency later becomes unacceptable, introduce a maintained table or materialized view in a separate migration.

If the product scope is reduced to a generic stop-arrival viewer, the four reference tables could be deferred and the reference foreign keys relaxed in a new migration. That conflicts with `project-v2.md`, which requires versioned, browsable bus topology and direction-aware analysis, so it is not the recommended project path.

## Implementation sequence

### 1. Reference-data importer

Create these boundaries:

```text
domain/BusReferencePort.java
domain/ReferenceImportService.java
domain repositories/entities for reference versions, stops, services, route stops
infrastructure/datamall/DataMallBusReferenceAdapter.java
infrastructure/config/ReferenceImportSchedule.java
```

The adapter reads DataMall `BusStops`, `BusServices`, and paginated `BusRoutes`. Provider response records stay under `infrastructure/datamall`.

The domain service must:

1. Fetch all three datasets.
2. Canonicalize and checksum the full import.
3. Return early when the checksum matches the active version.
4. Insert a `CANDIDATE` reference version and all child rows in one transaction.
5. Validate that every route stop refers to an imported service and stop.
6. Validate configured monitored services/stops.
7. Mark the previous version `SUPERSEDED` and the candidate `ACTIVE` atomically.
8. Leave the prior active version untouched if fetching or validation fails.

Initial import can run at startup before target seeding. Subsequent refresh should be a configurable scheduled task, not a one-minute dynamic poll.

Acceptance:

- Exactly one active version exists per reference dataset.
- Partial imports never become active.
- Reimporting unchanged data creates no new active version.
- Services 190 and 972 have ordered stops for each published direction.

### 2. Replace route scanning in target seeding

Change `PollTargetService` to read the active `bus_route_stop` topology rather than calling DataMall directly.

Move the initial service list to typed configuration, for example:

```yaml
occ:
  ingestion:
    monitored-services: ["190", "972"]
```

Seed the union of stops for configured services. Existing `poll_target` rows are preserved. Insert missing targets; do not delete or silently re-enable targets that an operator disabled.

Acceptance:

- No hard-coded `190` or `972` remains in Java code.
- Seeding is repeatable and preserves operator changes.
- A missing active topology prevents seeding with a clear startup error.

### 3. Typed Bus Arrival parser

Add provider records under `infrastructure/datamall`, matching:

```text
BusStopCode
Services[]
  ServiceNo
  Operator
  NextBus / NextBus2 / NextBus3
    OriginCode, DestinationCode, EstimatedArrival, Monitored
    Latitude, Longitude, VisitNumber, Load, Feature, Type
```

Change `BusArrivalPort` to return a domain-safe snapshot containing the raw body and parsed arrivals, or split raw fetching and parsing into separate adapter methods. Provider DTOs must not escape the adapter.

Parsing rules:

- Preserve the raw response even if one nested prediction is invalid.
- Ignore entirely empty `NextBus2`/`NextBus3` structures.
- Reject or mark the poll `PARTIAL` when individual entries cannot be parsed.
- Preserve nullable/blank upstream values; do not invent ETAs, direction, location, or load.
- Use `Instant` for moments and UTC internally.
- Produce a stable checksum from canonical normalized fields.

Acceptance tests cover one service, multiple services, three arrivals, missing later arrivals, blank optional values, malformed timestamps, and unknown enum-like values.

### 4. Atomic poll persistence

Refactor `BusArrivalService` so one target poll is persisted transactionally:

1. Claim the `poll_run` idempotency key.
2. Call DataMall.
3. Save `raw_observation` once for a non-empty HTTP response.
4. Resolve the active reference version.
5. Convert valid predictions to `bus_arrival_observation` rows.
6. Set `records_received` to the normalized row count.
7. Finish the run as `SUCCESS`, `PARTIAL`, or `FAILED`.
8. Update `feed_status` in the same transaction as the completed result.

Clarify transaction boundaries before coding: the external HTTP request should not hold a database transaction open. Use a short transaction to claim the run, perform HTTP outside it, then a short transaction to persist the result.

Failure behavior:

- HTTP/network failure: completed `FAILED` run, increment feed failure count, no new observation.
- Valid HTTP response with zero services: save the raw observation and a successful zero-record run.
- Partially invalid response: save raw data and valid normalized rows, mark `PARTIAL`, retain an error summary.
- Database failure: roll back normalized/raw result together; leave an observable failed run through a separate completion transaction.

### 5. Feed health

Implement `FeedStatus` and its repository/service.

On success:

```text
status = HEALTHY
last_attempt_at = now
last_success_at = now
consecutive_failures = 0
message = null
```

On failure:

```text
last_attempt_at = now
last_success_at = unchanged
consecutive_failures += 1
status = DEGRADED or FAILED according to configured threshold
message = bounded, non-secret diagnostic text
```

Compute `stale` at read time from `last_success_at + stale_after_seconds`; do not store it.

### 6. Read-only API

Add API records under `api/dto`, controllers under `api`, query services under `domain`, and centralized `ProblemDetail` handling under `api/error`. Never serialize JPA entities directly.

All list endpoints return a page envelope:

```json
{
  "items": [],
  "page": 0,
  "size": 50,
  "totalElements": 0,
  "totalPages": 0
}
```

#### Current arrivals

```http
GET /api/v1/arrivals/current?stopCode=44009&serviceId=190
```

At least one of `stopCode` or `serviceId` should be required to prevent an unbounded dashboard query. Optional filters: `operator`, `directionId`. Response groups predictions by stop and service, ordered by `visitOrder`.

Include:

```text
stopCode, serviceId, operator, directionId
predictions[]: visitOrder, estimatedArrivalAt, originCode, destinationCode,
               latitude, longitude, monitored, loadCategory, feature, vehicleType
fetchedAt, ageSeconds, stale, classification, rawObservationId, pollRunId
```

#### Arrival history

```http
GET /api/v1/arrivals/history?stopCode=44009&serviceId=190&from=...&to=...&page=0&size=50
```

Require `from` and `to`, cap the range at 24 hours, cap page size, and order newest first.

#### Poll history and detail

```http
GET /api/v1/polls?scopeKey=stop:44009&outcome=FAILED&from=...&to=...&page=0&size=50
GET /api/v1/polls/{pollRunId}
```

The detail response can link to its raw observation ID, but must not embed a potentially large payload.

#### Raw observation detail

```http
GET /api/v1/observations/{observationId}
```

This is a diagnostic/provenance endpoint. Return metadata plus the JSON payload. Do not add an unrestricted endpoint that returns every raw payload. A paginated metadata-only search may be added if the dashboard needs it:

```http
GET /api/v1/observations?scopeKey=stop:44009&from=...&to=...&page=0&size=25
```

#### Poll targets

```http
GET /api/v1/poll-targets?page=0&size=100
```

Keep this read-only for the current phase. Target mutation requires authentication, validation against active topology, and an explicit admin API design.

#### Feed status

```http
GET /api/v1/feed-status?page=0&size=100
GET /api/v1/feed-status/{source}/{scopeKey}
```

Return last attempt/success, failure count, computed age/stale state, and bounded diagnostic message.

#### Reference topology

```http
GET /api/v1/reference/versions?dataset=BUS_TOPOLOGY&page=0&size=20
GET /api/v1/reference/stops?serviceId=190&directionId=1&page=0&size=100
GET /api/v1/reference/services?page=0&size=100
GET /api/v1/reference/services/{serviceId}/routes/{directionId}/stops
```

These satisfy the project requirement that imported topology be browsable and read-only.

Do not add an endpoint called `/everything`. It would mix large raw JSON, history, topology, and health into an unstable response. Provide bounded resource endpoints and let the frontend compose its dashboard.

### 7. OpenAPI and error behavior

Add `org.springdoc:springdoc-openapi-starter-webmvc-ui` at the project-pinned version and configure OpenAPI 3.1.

Document:

- Required filters and maximum page sizes.
- UTC `date-time` fields.
- `OBSERVATION_ONLY` classification.
- Staleness semantics.
- `400` invalid filter/range, `404` missing ID, and `503` no active reference topology where applicable.
- RFC 9457 `application/problem+json` error bodies.

Verify `/v3/api-docs` parses and contains stable operation IDs.

### 8. Retention

Add typed settings:

```yaml
occ:
  ingestion:
    retention: 24h
    retention-schedule: "0 15 * * * *"
```

Implement a scheduled domain service that deletes in bounded batches, oldest first:

1. `bus_arrival_observation` older than cutoff.
2. `raw_observation` older than cutoff and no longer referenced.
3. Completed `poll_run` older than cutoff and no longer referenced.

Do not delete `poll_target`, `feed_status`, reference topology, or any non-telemetry data. Do not use database cascading that could make the deletion scope implicit. Log only counts and cutoff time to the terminal.

Retention must be retry-safe and must not delete the latest known observation solely because the newest poll failed. The current-state view should continue showing the newest record still within retention and mark it stale.

## Test plan

### Unit tests

- DataMall parser maps all three prediction positions.
- Blank later predictions create no rows.
- Unknown optional values remain representable.
- Feed status transitions on success/failure and computes staleness from an injected `Clock`.
- Retention cutoff boundary is exact and testable with a fixed `Clock`.
- API query validation rejects missing filters, excessive page sizes, and ranges over 24 hours.

### API slice tests

- Current-arrival filters and response grouping.
- Pagination envelope and ordering.
- Raw observation detail returns JSON without exposing internal entity fields.
- `ProblemDetail` responses for invalid inputs and missing resources.

### PostgreSQL integration tests

- V005/V006 apply on top of V001-V004.
- Full reference import activates atomically.
- A fake multi-service DataMall response creates one raw row and the expected normalized rows.
- Duplicate minute-bucket polling does not make a second request or duplicate records.
- Current view selects the newest observation per key.
- Failed polls do not replace last successful current state.
- Retention removes expired telemetry in FK-safe order and preserves reference/configuration tables.
- Repository queries use PostgreSQL JSONB, timestamps, indexes, and PostGIS mappings correctly.

### Verification commands

```powershell
cd services/ingestion
.\mvnw.cmd verify
```

Manually verify the generated OpenAPI document and representative current/history queries against synthetic data. A real DataMall smoke test is separate evidence and must not be required for deterministic automated tests.

## Delivery slices

1. **Tracer slice:** V005/V006, reference importer for required services/stops, parse one fake response, persist normalized rows, and serve `GET /api/v1/arrivals/current` from the current-state view.
2. **Complete dynamic ingestion:** multi-service parsing, idempotency, partial failures, and feed status.
3. **Read API expansion:** history, polls, raw detail, targets, and topology endpoints with OpenAPI.
4. **Lifecycle:** 24-hour retention, metrics/logging, and full integration verification.

Each slice must leave the service runnable and preserve V001-V004 data. Do not delete existing raw observations during migration; normalization begins for new polls unless a separately approved backfill is added.

## Definition of done

- Every successful Bus Arrival payload is traceable from normalized rows to raw payload and poll run.
- `records_received` equals normalized prediction count.
- Current state returns the latest valid predictions without overwriting history.
- Failed polls retain the last successful state and expose staleness.
- Duplicate same-minute polls are idempotent.
- Telemetry older than 24 hours is removed without touching reference/configuration data.
- Reference topology is versioned, active, and browsable.
- All list APIs are bounded and paginated.
- OpenAPI and RFC 9457 errors match actual behavior.
- Maven verification and PostgreSQL integration tests pass.
