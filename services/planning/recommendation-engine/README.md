# OCC Recommendation Engine

The recommendation engine implements WQAP-4, **Mitigation Action
Recommendations**. Its current scope is to accept a confirmed simulated
bus-bunching event and its root-cause diagnosis, apply deterministic safety and
operational constraints, and return one best mitigation recommendation for
controller review.

The service is intentionally limited to bus-bunching for the first delivery,
while its domain model leaves room for other incident types and mitigation
strategies later.

## Current status

The deterministic contract milestone (Phases 1–6) is implemented and tested.
The service currently provides:

- a Spring Boot 4.1.1 and Java 25 Maven application;
- an internal HTTP endpoint for one recommendation;
- validated request and response records;
- a deterministic bus-bunching recommendation policy;
- fixture-backed operational constraints and impact estimates;
- `HOLD` and `DWELL_ADJUSTMENT` policy support;
- RFC 9457-style problem responses;
- generated OpenAPI documentation;
- unit, MVC, fixture-adapter, and full application integration tests; and
- a multi-stage Docker image running as a non-root user.

The following work is not implemented yet:

- WQAP-3 delivery to this endpoint;
- PostgreSQL persistence and durable idempotency;
- a recommendation-owned database schema and role;
- Docker Compose registration and centralized environment configuration;
- Gemini/Spring AI candidate generation and model audit records;
- an Operations service adapter; and
- `STANDBY_DISPATCH` behavior.

WQAP-3 is still in progress. The request contract described here is the current
recommendation-engine contract, not confirmation that WQAP-3 already publishes
it.

## Service boundary

The service accepts a root-cause diagnosis together with the bunching event it
describes. It does not detect bunching and does not diagnose the cause itself.

```text
WQAP-2 detection output
        |
        | future WQAP-3 consumption
        v
WQAP-3 root-cause diagnosis
        |
        | POST confirmed simulated event + diagnosis
        v
recommendation-engine
        |
        | deterministic eligibility, safety and action limits
        v
one recommendation for controller review
```

WQAP-2 currently labels its live DataMall output `OBSERVATION_ONLY`. That output
may support monitoring and diagnosis, but it must not be relabelled as
`SIMULATION` merely to obtain a control action. The current engine returns no
action for observation-only evidence.

## API contract

### Create a recommendation

```http
POST /internal/v1/bunching-recommendations
Content-Type: application/json
Idempotency-Key: <UUID>
```

The caller must provide a UUID `Idempotency-Key`. At present, this UUID becomes
the recommendation ID and makes deterministic repeated calls produce the same
identifier. The service does **not** yet persist the original request or
response. Durable replay and rejection of the same key with different content
must be added before nondeterministic model output is enabled.

The complete Route 190 example request is maintained at
[`src/test/resources/fixtures/requests/confirmed-bunching-recommendation.json`](src/test/resources/fixtures/requests/confirmed-bunching-recommendation.json).

```json
{
  "requestId": "6e2f6b21-9c43-4c17-93c5-10b2d820b9a7",
  "classification": "SIMULATION",
  "bunchingEvent": {
    "bunchingEventId": "c5934f15-4a65-4e94-87ea-a5a64a7dd4c1",
    "serviceId": "190",
    "directionId": 1,
    "monitoredStopCode": "44251",
    "status": "CONFIRMED",
    "expectedHeadwaySeconds": 600,
    "observedHeadwaySeconds": [276, 221],
    "firstConfirmedAt": "2026-09-27T23:45:02Z",
    "evidenceIds": [
      "route190-arrival-20260928T074402+0800",
      "route190-arrival-20260928T074502+0800"
    ]
  },
  "diagnosis": {
    "diagnosisId": "f2b8d1b5-c24c-48bb-8a66-bff7be1e9e73",
    "likelyCause": "A vehicle breakdown delayed leader SBS8027Y, allowing follower SBS5898U to close the gap.",
    "confidence": 0.82,
    "evidenceIds": ["route190-breakdown-delay-sbs8027y"],
    "limitations": [
      "The diagnosis is supplied by the shared deterministic simulation fixture."
    ]
  }
}
```

### Input meaning

| Field | Meaning |
|---|---|
| `requestId` | Caller-generated correlation ID for this request. |
| `classification` | `SIMULATION` can be actionable; `OBSERVATION_ONLY` cannot produce an action. |
| `bunchingEventId` | Stable UUID identifying the confirmed bunching event. |
| `serviceId` | Bus service identifier, such as `190`. |
| `directionId` | Route direction, currently constrained to `1` or `2`. |
| `monitoredStopCode` | Stop at which the bunching observations were evaluated. |
| `status` | Must be `CONFIRMED` to produce a recommendation. |
| `expectedHeadwaySeconds` | Planned headway used to calculate the gap deficit. |
| `observedHeadwaySeconds` | Ordered observations; the latest value is used by the policy. |
| `firstConfirmedAt` | UTC timestamp at which the event became confirmed. |
| event `evidenceIds` | Stable references to the observations supporting detection. |
| `diagnosisId` | Stable UUID assigned by the diagnosis producer. |
| `likelyCause` | Human-readable root-cause conclusion. |
| `confidence` | Diagnosis confidence from `0.0` through `1.0`. It is reported but is not currently an action threshold. |
| diagnosis `evidenceIds` | Stable references supporting the root-cause conclusion. |
| `limitations` | Known gaps or qualifications that must remain visible to the controller. |

The current policy evaluates every confirmed diagnosis. It does not suppress a
recommendation solely because diagnosis confidence is below an arbitrary
threshold.

### Successful response

The endpoint returns HTTP `200` and exactly one recommendation. Important Route
190 result fields are:

```json
{
  "recommendationId": "f07c921a-bd3b-4a97-8b17-a384ef1269a7",
  "classification": "SIMULATION",
  "incidentType": "BUS_BUNCHING",
  "status": "RECOMMENDED",
  "mitigationType": "HOLD",
  "targetBusReference": "SBS5898U",
  "controlStopCode": "44241",
  "adjustmentSeconds": 120,
  "expectedImpact": {
    "headwayBeforeSeconds": 221,
    "headwayAfterSeconds": 341,
    "queueBeforePassengers": 38,
    "queueAfterPassengers": 24,
    "passengerMinutesBefore": 190,
    "passengerMinutesAfter": 128
  }
}
```

The actual response also contains `requestId`, an explanation with rationale,
cause, confidence, trade-offs and limitations, and the combined evidence IDs.

### Failure responses

| Status | Meaning |
|---|---|
| `400 Bad Request` | Missing idempotency key, malformed JSON, unsupported enum value, or failed field validation. |
| `422 Unprocessable Content` | The input is valid but cannot produce a validated actionable recommendation. |

Examples of `422` conditions include:

- `classification` is `OBSERVATION_ONLY`;
- the event is not `CONFIRMED`;
- no fixture matches the service, direction and monitored stop;
- crowd safety did not pass;
- operational validation was rejected; or
- the latest headway has no positive deficit.

Errors use `application/problem+json`. When supplied, `X-Correlation-ID` is
copied into the problem response.

## Deterministic policy

A recommendation is eligible only when all of these conditions hold:

1. The source classification is `SIMULATION`.
2. The bunching event status is `CONFIRMED`.
3. A fixture exists for `(serviceId, directionId, monitoredStopCode)`.
4. The fixture's operational validation status is `VALIDATED`.
5. The fixture's crowd-safety status is `PASSED`.
6. The expected headway is greater than the latest observed headway.

The following-bus state selects the mitigation:

| Following-bus state | Mitigation |
|---|---|
| `READY_TO_DEPART` | `HOLD` |
| `BOARDING_AT_CONTROL_STOP` | `DWELL_ADJUSTMENT` |

The adjustment is calculated as:

```text
min(headway deficit, fixture action limit, 120-second absolute limit)
```

Only `HOLD` has a complete fixture-backed HTTP demonstration today.
`DWELL_ADJUSTMENT` is covered by domain tests. `STANDBY_DISPATCH` is reserved in
the enum for future work and is not supported by the current policy.

## Route 190 demonstration fixture

The bundled fixture aligns its identifiers and first action with the shared
simulation under `SimulatedData/route190/`:

- monitored stop `44251`;
- leader `SBS8027Y`;
- follower `SBS5898U`;
- approved control stop `44241`; and
- first hold of 120 seconds.

The fixture's `221 -> 341` headway is a deterministic estimate for the first
hold, not an observed post-action result. Queue and passenger-minute values are
also synthetic single-action demo estimates. They must not be presented as
measurements from the complete shared multi-hold simulation.

Detailed provenance is recorded in
[`src/main/resources/fixtures/README.md`](src/main/resources/fixtures/README.md).

## Build and test

### Requirements

- JDK 25
- Maven 3.9 or newer

Confirm the environment:

```powershell
java -version
mvn -version
```

From `services/planning/recommendation-engine` run:

```powershell
mvn clean verify
```

The current suite contains 19 tests:

| Test area | Count | Coverage |
|---|---:|---|
| Controller MVC | 4 | Success, validation, missing header and unavailable action |
| Domain policy | 8 | Action choice, limits and rejection rules |
| Fixture adapter | 2 | Known and unknown fixture lookup |
| Full application integration | 5 | Fixture flow, repeatability, rejection and OpenAPI |

`mvn clean verify` runs both Surefire unit tests and the Failsafe integration
test ending in `IT`.

## Run locally

From the service directory:

```powershell
mvn spring-boot:run
```

The current default port is `8083`.

- Health: `http://localhost:8083/actuator/health`
- Swagger UI: `http://localhost:8083/swagger-ui.html`
- Internal OpenAPI JSON: `http://localhost:8083/v3/api-docs/internal`

Example PowerShell request:

```powershell
$body = Get-Content `
  src/test/resources/fixtures/requests/confirmed-bunching-recommendation.json `
  -Raw

Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8083/internal/v1/bunching-recommendations `
  -ContentType application/json `
  -Headers @{ "Idempotency-Key" = "f07c921a-bd3b-4a97-8b17-a384ef1269a7" } `
  -Body $body
```

## Docker

Build from the service directory:

```powershell
docker build -t occ-recommendation-engine:local .
docker run --rm -p 8083:8083 occ-recommendation-engine:local
```

The image uses a Maven build stage, a Java 25 runtime, and the non-root user
`10001`. The service has not yet been registered in the repository's local
Compose stack.

## Package structure

```text
src/main/java/com/csd/occ/planning/recommendationengine/
├── RecommendationEngineApplication.java
├── api/
│   ├── dto/                 HTTP records and mapping
│   └── error/               Problem response handling
├── domain/                  Policy, service and domain records
└── infrastructure/
    ├── config/              Spring and OpenAPI configuration
    └── fixture/             Classpath mitigation-context adapter
```

The domain depends on the `BunchingMitigationContextPort`. The classpath fixture
adapter is its current implementation. A future Operations service or database
adapter can implement the same boundary without moving operational concerns into
the policy.

## Configuration today

The current configuration is deliberately small:

| Setting | Current value |
|---|---|
| Application name | `recommendation-engine` |
| Port | `${SERVER_PORT:8083}` |
| Fixture location | `classpath:fixtures/bunching-mitigation-context.json` |
| Health endpoints | `health`, `info` |

Repository conventions added after the initial implementation require
recommendation-specific environment variables, a Maven wrapper, `.dockerignore`,
root `.env.example` entries and Compose registration. These remain follow-up
work and should be completed before deployment-oriented Phase 7 work.

## Persistence status

The service currently has no database dependency and does not write any tables.
The repository already contains `planning`/`occ_planning`, but the team still
needs to confirm whether this independently deployed service will use that
schema or receive a feature-owned schema such as
`recommendation`/`occ_recommendation`.

Once ownership is decided, this service should own its Flyway migrations and no
other service should mutate its tables.

## WQAP-3 integration status

No WQAP-3 implementation update is available yet. The agreed direction is that
WQAP-3 will call this service after producing a diagnosis. Before connecting the
services, both teams must verify:

- the exact endpoint and network URL;
- field names, enum values and timestamp format;
- how a WQAP-3 diagnosis is associated with a confirmed simulation event;
- stable event, diagnosis and evidence identifiers;
- timeout, retry and idempotency behavior; and
- handling of `400` and `422` responses.

The current request fixture is the executable consumer example until WQAP-3
publishes or consumes a finalized cross-service contract.

## Phase 7 boundary

Gemini/Spring AI is intended for Phase 7, but model output must not bypass the
deterministic safety policy. The intended boundary is:

1. Gemini proposes structured mitigation candidates or explanation content.
2. Schema validation rejects malformed model output.
3. Deterministic code applies classification, crowd-safety, operational and
   adjustment limits.
4. The service returns only the best validated recommendation.
5. Model, prompt/schema version, latency, usage, evidence and validation outcome
   are persisted for audit and replay.

Durable idempotency and database ownership should be resolved before enabling
nondeterministic model calls.

## Guidance for contributors and AI agents

- Follow the repository root `AGENTS.md` and `docs/CONVENTIONS.md`.
- Treat this README and the generated OpenAPI document as the current service
  contract; keep both synchronized with API changes.
- Preserve the `OBSERVATION_ONLY` safety boundary.
- Do not invent operational facts, bus identifiers or impact measurements.
- Keep model generation separate from deterministic validation.
- Add or update unit and integration tests whenever policy or API behavior
  changes.
- Run `mvn clean verify` before committing.
- Do not commit `target/`, credentials, local `.env` files or model secrets.

## Related repository references

- [`docs/plans/sprint-1/sprint-1.md`](../../../docs/plans/sprint-1/sprint-1.md)
- [`docs/CONVENTIONS.md`](../../../docs/CONVENTIONS.md)
- [`SimulatedData/README.md`](../../../SimulatedData/README.md)
- [`SimulatedData/route190/bunching/scenario.json`](../../../SimulatedData/route190/bunching/scenario.json)
- [`SimulatedData/route190/bunching_mitigated/scenario.json`](../../../SimulatedData/route190/bunching_mitigated/scenario.json)
