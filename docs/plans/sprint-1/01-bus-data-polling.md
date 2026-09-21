# Plan 3 — Bus-Data Polling Service

## Goal

Deliver an independently deployable Ingestion service that imports read-only bus reference topology and polls Bus Arrival v3 every minute for configured stops, preserving raw provenance, normalized records, idempotency, freshness, and failure state.

## Owners and dependencies

- Suggested staffing: two people.
- Starts on Day 1 using stubbed DataMall responses.
- Depends on Plan 1 for the isolated-service template, database, JWT convention, Caddy, and CI conventions.
- Publishes fixture-backed OpenAPI early so Plan 2 is not blocked.

## Week 1 feed scope

- Required static imports: bus stops, services, and ordered route stops.
- Required dynamic poll: Bus Arrival v3 for configured monitored stops.
- Deferred: traffic incidents, speed bands, train alerts, crowd density, and historical passenger-volume imports.
- Dynamic observations are display-only and cannot initiate planning or alter simulations.

## Database schema

```sql
create table ingestion.poll_run (
    id uuid primary key,
    source varchar(40) not null,
    scope_key varchar(120) not null,
    idempotency_key varchar(120) not null unique,
    started_at timestamptz not null,
    completed_at timestamptz,
    outcome varchar(20) not null
        check (outcome in ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED')),
    http_status integer,
    records_received integer not null default 0,
    error_code varchar(80),
    error_message varchar(500)
);

create table ingestion.raw_observation (
    id uuid primary key,
    poll_run_id uuid not null references ingestion.poll_run(id),
    source varchar(40) not null,
    source_key varchar(200) not null,
    request_parameters jsonb not null,
    payload jsonb not null,
    payload_checksum char(64) not null,
    observed_at timestamptz,
    fetched_at timestamptz not null,
    ingested_at timestamptz not null,
    http_status integer not null,
    classification varchar(30) not null default 'OBSERVATION_ONLY',
    unique (poll_run_id, source_key, payload_checksum)
);

create table ingestion.feed_status (
    source varchar(40) not null,
    scope_key varchar(120) not null,
    status varchar(20) not null
        check (status in ('HEALTHY', 'DEGRADED', 'FAILED', 'NEVER_RUN')),
    last_attempt_at timestamptz,
    last_success_at timestamptz,
    consecutive_failures integer not null default 0,
    stale_after_seconds integer not null,
    message varchar(500),
    updated_at timestamptz not null,
    primary key (source, scope_key)
);

create table ingestion.reference_dataset_version (
    id uuid primary key,
    dataset varchar(40) not null,
    version_key varchar(80) not null,
    checksum char(64) not null,
    status varchar(20) not null
        check (status in ('CANDIDATE', 'ACTIVE', 'SUPERSEDED', 'REJECTED')),
    imported_at timestamptz not null,
    activated_at timestamptz,
    unique (dataset, version_key)
);

create unique index uq_reference_dataset_version_active
    on ingestion.reference_dataset_version (dataset)
    where status = 'ACTIVE';

create table ingestion.bus_stop (
    reference_version_id uuid not null
        references ingestion.reference_dataset_version(id),
    stop_code varchar(12) not null,
    road_name varchar(160) not null,
    description varchar(200) not null,
    location geometry(Point, 4326) not null,
    imported_at timestamptz not null,
    primary key (reference_version_id, stop_code)
);

create table ingestion.bus_service (
    reference_version_id uuid not null
        references ingestion.reference_dataset_version(id),
    service_id varchar(20) not null,
    operator varchar(40) not null,
    category varchar(40),
    imported_at timestamptz not null,
    primary key (reference_version_id, service_id, operator)
);

create table ingestion.bus_route_stop (
    reference_version_id uuid not null
        references ingestion.reference_dataset_version(id),
    service_id varchar(20) not null,
    operator varchar(40) not null,
    direction_id smallint not null,
    stop_sequence integer not null,
    stop_code varchar(12) not null,
    distance_km numeric(8,3),
    imported_at timestamptz not null,
    primary key
        (reference_version_id, service_id, operator, direction_id, stop_sequence),
    unique
        (reference_version_id, service_id, operator, direction_id,
         stop_code, stop_sequence),
    foreign key (reference_version_id, service_id, operator)
        references ingestion.bus_service
        (reference_version_id, service_id, operator),
    foreign key (reference_version_id, stop_code)
        references ingestion.bus_stop (reference_version_id, stop_code)
);

create table ingestion.poll_target (
    source varchar(40) not null,
    scope_key varchar(120) not null,
    stop_code varchar(12),
    enabled boolean not null default true,
    poll_interval_seconds integer not null default 60,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (source, scope_key)
);

create table ingestion.bus_arrival_observation (
    id uuid primary key,
    raw_observation_id uuid not null references ingestion.raw_observation(id),
    poll_run_id uuid not null references ingestion.poll_run(id),
    reference_version_id uuid not null
        references ingestion.reference_dataset_version(id),
    stop_code varchar(12) not null,
    service_id varchar(20) not null,
    operator varchar(40),
    direction_id smallint,
    visit_order smallint not null check (visit_order between 1 and 3),
    estimated_arrival_at timestamptz,
    latitude numeric(10,7),
    longitude numeric(10,7),
    load_category varchar(20),
    feature varchar(40),
    observed_at timestamptz,
    fetched_at timestamptz not null,
    ingested_at timestamptz not null,
    record_checksum char(64) not null,
    unique (raw_observation_id, service_id, visit_order, record_checksum),
    foreign key (reference_version_id, stop_code)
        references ingestion.bus_stop (reference_version_id, stop_code)
);

create index ix_bus_arrival_stop_time
    on ingestion.bus_arrival_observation (stop_code, fetched_at desc);

create index ix_bus_arrival_service_time
    on ingestion.bus_arrival_observation
    (service_id, direction_id, fetched_at desc);
```

`direction_id` may be null when DataMall evidence cannot determine it. Downstream components must treat null direction as insufficient evidence rather than guessing.

## API contract

### `GET /api/v1/ingestion/feeds`

Returns feed status records. Authenticated `ADMIN` and `CONTROLLER` users may read them.

### `GET /api/v1/ingestion/stops`

Searches imported stops by code or description. Parameters: `query`, `page`, `size`; cap size at 100.

### `GET /api/v1/ingestion/stops/{stopCode}/arrivals`

Returns the latest successful normalized snapshot, grouped by service and direction where known. Include `fetchedAt`, `stale`, and the contributing raw observation ID. A failed current poll must not turn the response into an empty successful snapshot.

### `POST /api/v1/ingestion/admin/polls/{source}`

Admin-only manual trigger for diagnostics. Require `Idempotency-Key`; return `202` with a poll-run ID. Reject unsupported sources and overlapping identical polls.

### `GET /api/v1/ingestion/polls/{pollRunId}`

Returns diagnostic status without exposing secrets or full raw payloads.

## Polling behavior

- Use a fixed one-minute schedule for enabled Bus Arrival targets.
- Derive scheduler idempotency keys from source, scope, and scheduled minute; manual triggers use the caller's `Idempotency-Key`.
- Acquire a database advisory lock or equivalent lease per `source/scopeKey` so multiple instances do not poll the same target simultaneously.
- Apply connect and response timeouts, a small bounded retry with jitter, response-size limits, and schema validation.
- Write the poll run, raw payload, normalized rows, and feed-status transition transactionally where practical.
- Repeated identical payloads are idempotent through checksums and uniqueness constraints.
- A failure updates `feed_status` and `poll_run` but never deletes or replaces the latest successful observations.
- Compute stale state from server time and `lastSuccessAt`; do not persist a drifting boolean.
- Log source, scope, poll-run ID, duration, status, and record count, never the API key or raw authorization header.
- Static reference imports must handle DataMall pagination and stage/validate data before replacing the current reference version.
- `poll_target.stop_code` is intentionally independent of one reference-version foreign key; activation validates every enabled target against the new active topology before switching versions.

## Tasks

### Day 1 — Contract and connector skeleton

- Complete the independent `services/ingestion` Spring Boot project from the reviewed service template.
- Define connector ports, DataMall adapter, typed configuration, timeout policy, and redacted logging.
- Commit Ingestion OpenAPI with deterministic examples for frontend fixtures.
- Add WireMock fixtures for success, pagination, empty valid response, malformed response, timeout, rate limit, and server error.
- Add baseline Flyway migration and Testcontainers setup.

### Day 2 — Static reference import

- Implement paginated bus-stop, bus-service, and bus-route imports.
- Validate stop codes, coordinates, service IDs, directions, sequence continuity, and referenced stops.
- Compute a source-version checksum and stage the import before activating it.
- Persist PostGIS stop points with SRID 4326.
- Seed poll targets for stops required by the 190 and 972 example profiles.

Verification:

- Reimporting the same source version creates no duplicate topology.
- Invalid pages fail the candidate version without damaging the active version.
- Ordered route queries are stable and deterministic.

### Day 3 — Dynamic Bus Arrival polling

- Implement scheduled target discovery and per-target polling.
- Persist `poll_run`, `raw_observation`, normalized arrivals, and `feed_status`.
- Implement payload and record checksums and idempotent retry behavior.
- Preserve ambiguous or missing direction as null.
- Add manual admin trigger and poll-status endpoint.

Verification:

- Two identical fetches do not create duplicate normalized facts.
- Concurrent scheduler/manual triggers do not double-poll the same target.
- Malformed responses are retained diagnostically but do not become normalized observations.

### Day 4 — Read API, security, and observability

- Implement feed-status, stop-search, latest-arrival, and poll-run APIs.
- Apply Cognito bearer validation and role policies from Plan 1.
- Add liveness, readiness, scheduler, latency, success/failure, records, and stale-feed metrics.
- Add request IDs and RFC 9457 errors.
- Integrate behind Caddy and generate the final OpenAPI document.

### Day 5 — Real-feed and end-to-end proof

- Run a controlled import and configured-stop poll with the real DataMall key.
- Verify database provenance from API response to normalized row, raw observation, and poll run.
- Disconnect or invalidate the stubbed upstream and prove stale last-known-good behavior.
- Integrate the dashboard from Plan 2.
- Document connector configuration, rate-limit assumptions, replay fixtures, manual recovery, and known source limitations.

## Required tests

- Unit tests for pagination, validation, checksum generation, normalization, and stale calculation.
- WireMock tests for timeouts, retries, malformed data, rate limits, and partial pagination failure.
- Testcontainers tests for migrations, constraints, idempotency, locks, PostGIS coordinates, and latest-success queries.
- MVC/security tests for read and admin endpoints.
- One manual real-DataMall smoke run; automated CI remains fixture-only.

## Exit criteria

- Static bus topology imports and can be queried in deterministic stop order.
- Configured stops poll every minute without overlapping duplicate work.
- Raw and normalized data remain traceable through poll-run and observation IDs.
- Duplicate fetches are idempotent.
- Failures preserve last-known-good observations and expose stale/degraded health.
- Authenticated APIs match committed OpenAPI and power the Week 1 dashboard.
- Tests, container build, local Compose, and deployed smoke checks pass.
