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
- unit, MVC, fixture-adapter, and full application integration tests;
- a Maven 3.9.11 wrapper and repository-standard environment configuration;
- a dedicated `recommendation` PostgreSQL schema owned by `occ_recommendation`;
- Flyway-managed recommendation audit records and durable idempotency;
- a provider-neutral AI candidate-generation port and strict candidate contract,
  disabled by default;
- deterministic candidate validation with explicit rejection reasons;
- deterministic candidate/fallback orchestration that preflights eligibility
  before any provider call;
- a conditionally registered Gemini adapter using Spring AI 2.0.1 and
  provider-native structured output;
- durable, sanitized model-invocation audit records linked to stored
  recommendations;
- a multi-stage Docker image running as a non-root user.

The repository configuration and local database provisioning have been applied
and verified. The Maven wrapper resolves Maven 3.9.11, `clean verify` passes
all 56 tests, and the PostgreSQL integration suite applies the migrations and
exercises persistence using only the `occ_recommendation` role.

The following work is not implemented yet:

- WQAP-3 delivery to this endpoint;
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

### How the recommendation engine works

For each request, the service performs the following flow:

1. It validates the HTTP header and the event and diagnosis fields.
2. It checks PostgreSQL for an existing record with the supplied
   `Idempotency-Key`. An exact replay returns the stored response without
   evaluating the policy or calling Gemini again.
3. It loads the matching operational context. Today this comes from the
   deterministic fixture; a future Operations service adapter can replace it.
4. It runs the deterministic policy first. Only a confirmed `SIMULATION` event
   with validated operational and crowd-safety constraints is actionable.
5. When AI is disabled, the deterministic recommendation is used directly.
   When AI is enabled, Gemini may propose one structured candidate, but that
   candidate must exactly match the deterministic action and its limits.
6. Missing, invalid, unsafe, mismatched or failed model output falls back to the
   deterministic recommendation. Gemini cannot override the safety policy.
7. The service transactionally stores the recommendation and, when a provider
   was invoked, a sanitized model-call audit. It then returns exactly one best
   recommendation for controller review.

WQAP-3 must integrate through the HTTP API. It must not read or write the
recommendation engine's PostgreSQL schema directly.

## API contract

### Create a recommendation

```http
POST /internal/v1/bunching-recommendations
Content-Type: application/json
Idempotency-Key: <UUID>
```

The caller must provide a UUID `Idempotency-Key`. This UUID becomes the
recommendation ID. The service stores the validated request fingerprint and
recommendation before returning success. Reusing the key with the same request
returns the original stored response; reusing it with different request content
returns `409 Conflict`. Requests that produce no actionable recommendation are
not stored.

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
| `409 Conflict` | The idempotency key already belongs to a different validated request. |
| `413 Payload Too Large` | The JSON body exceeds `OCC_RECOMMENDATION_MAX_REQUEST_BODY_BYTES` (65,536 bytes by default). |
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

The recommendation endpoint checks both declared `Content-Length` and bytes actually
read, so chunked requests cannot bypass the body limit. Request evidence is bounded to
100 IDs of at most 200 characters; headway history contains 2–10 values. Before a
provider call, the UTF-8 system-plus-user prompt is limited by
`OCC_RECOMMENDATION_MAX_PROMPT_INPUT_BYTES` (65,536 bytes by default). Both limits are
typed settings, validated at startup, mapped in Compose and inventoried in `.env.example`;
an oversized prompt preserves the existing deterministic fallback.

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
- Docker Desktop for container or Compose runs

Confirm the environment:

```powershell
java -version
./mvnw.cmd -version
```

From `services/planning/recommendation-engine` run:

```powershell
./mvnw.cmd clean verify
```

The current suite contains 56 tests:

| Test area | Count | Coverage |
|---|---:|---|
| Controller MVC | 5 | Success, validation, missing header, unavailable action and idempotency conflict |
| Domain policy | 8 | Action choice, limits and rejection rules |
| AI candidate contract | 4 | Permitted actions, required text and adjustment limits |
| AI candidate validation | 8 | Source, event, safety, target, action and adjustment constraints |
| Candidate/fallback orchestration | 8 | Disabled, unavailable, accepted, empty, rejected, mismatched, failed and non-actionable paths |
| Durable recommendation service | 2 | Associated model audit persistence and idempotent replay short-circuiting |
| Gemini configuration and adapter | 7 | Typed settings, secret redaction, model options, native schema, failure categories and domain conversion |
| Fixture adapter | 2 | Known and unknown fixture lookup |
| Full application integration | 12 | Configuration default, Flyway, transactional recommendation/model audit persistence, replay, conflict, rejection and OpenAPI |

`./mvnw.cmd clean verify` runs both Surefire unit tests and the Failsafe integration
test ending in `IT`.

## Run locally

### Prepare the root `.env`

Create a private environment file from the repository inventory. Run this only
if `.env` does not already exist:

```powershell
Copy-Item .env.example .env
```

For deterministic local execution, confirm these entries in the ignored
repository-root `.env`:

```properties
POSTGRES_DB=occ
POSTGRES_USER=occ_admin
POSTGRES_PASSWORD=<private-local-admin-password>

OCC_RECOMMENDATION_DB_URL=jdbc:postgresql://localhost:5432/occ
OCC_RECOMMENDATION_DB_USERNAME=occ_recommendation
OCC_RECOMMENDATION_DB_PASSWORD=<private-local-recommendation-password>
OCC_RECOMMENDATION_AI_ENABLED=false
OCC_RECOMMENDATION_GEMINI_API_KEY=change-me
```

`POSTGRES_PASSWORD` is the local database administrator password.
`OCC_RECOMMENDATION_DB_PASSWORD` is the password for the service-owned
`occ_recommendation` role. They may have the same local value, although they
represent different roles. If an existing PostgreSQL volume already contains
the role, use the password with which that role was originally created; the
idempotent schema initialization does not rotate it.

The database URL above is for a service started directly from the host. Compose
automatically replaces it with the container-internal `postgres` hostname.
Do not commit `.env` or place real passwords or API keys in `.env.example`.

To exercise the live Gemini path instead, change only these entries:

```properties
OCC_RECOMMENDATION_AI_ENABLED=true
OCC_RECOMMENDATION_GEMINI_API_KEY=<your-private-google-ai-studio-key>
```

The model and guardrail settings already have verified defaults in
`.env.example`. A Gemini key is not required for deterministic execution or the
automated test suite.

### Run from Maven

From the service directory:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
./mvnw.cmd spring-boot:run
```

PostgreSQL and the recommendation schema must already be running. From the
repository root, provision them first with:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml up -d postgres recommendation-schema-init
```

The process listens on internal port `8003` by default. The `local` profile imports the
repository-root `.env`, including the private database password. Host-native
runs use `jdbc:postgresql://localhost:5432/occ` and `occ_recommendation` by
default; all settings remain overridable through environment variables.

Gemini remains opt-in. For a local provider call, set these values only in the
ignored repository-root `.env`:

```properties
OCC_RECOMMENDATION_AI_ENABLED=true
OCC_RECOMMENDATION_GEMINI_API_KEY=<your-private-google-ai-studio-key>
```

When the flag is `false`, no Gemini client or adapter bean is created and no API
key is required. When the flag is `true`, startup rejects a missing, blank or
`change-me` key. Enable it in shared environments only through an intentional
deployment decision because requests can incur provider cost. Automated tests
use a mocked Spring AI model and never make a billable Gemini request.

- Health: `http://localhost:8003/actuator/health`
- Swagger UI: `http://localhost:8003/swagger-ui.html`
- Internal OpenAPI JSON: `http://localhost:8003/v3/api-docs/internal`

Example PowerShell request:

```powershell
$body = Get-Content `
  src/test/resources/fixtures/requests/confirmed-bunching-recommendation.json `
  -Raw

Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8003/internal/v1/bunching-recommendations `
  -ContentType application/json `
  -Headers @{ "Idempotency-Key" = "f07c921a-bd3b-4a97-8b17-a384ef1269a7" } `
  -Body $body
```

For a Compose-hosted service, use
`http://localhost:6769/internal/v1/bunching-recommendations` instead. A WQAP-3
container on the same Compose network must use
`http://recommendation-engine:8003/internal/v1/bunching-recommendations`.

## Docker

Build from the service directory:

```powershell
docker build -t occ-recommendation-engine:local .
```

The application requires PostgreSQL at startup. Use the Compose command below
for a runnable container; a standalone `docker run` must explicitly provide a
reachable database URL, username and password.

The image uses a Maven build stage, a Java 25 runtime, and the non-root user
`10001`. Its `.dockerignore` excludes Git metadata and Maven build output from
the Docker build context.

From the repository root, the service can also be built and started through the
local Compose stack:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml up --build recommendation-engine
```

Compose publishes the service on `http://localhost:6769` by default and maps
that host port to the process's internal port `8003`.

The root `.env` must include a non-default local value for
`OCC_RECOMMENDATION_DB_PASSWORD`. Provision or verify the dedicated schema for
an existing PostgreSQL volume with:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml up recommendation-schema-init
```

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
    ├── fixture/             Classpath mitigation-context adapter
    └── persistence/         JDBC recommendation-record adapter
```

The domain depends on `BunchingMitigationContextPort` for validated operational
inputs and `RecommendationRecordPort` for durable audit records. The classpath
fixture and JDBC implementations remain infrastructure adapters. A future
Operations service adapter can replace the fixture without moving operational
concerns into the policy.

## Configuration today

The current configuration is deliberately small:

| Setting | Current value |
|---|---|
| Application name | `recommendation-engine` |
| Public Compose port | `${OCC_RECOMMENDATION_PORT:6769}` |
| Internal/process port | `${OCC_RECOMMENDATION_INTERNAL_PORT:8003}` |
| Fixture location | `${OCC_RECOMMENDATION_FIXTURE_LOCATION:classpath:fixtures/bunching-mitigation-context.json}` |
| AI candidate generation | `${OCC_RECOMMENDATION_AI_ENABLED:false}` |
| Gemini API key | `OCC_RECOMMENDATION_GEMINI_API_KEY` (required secret only when AI is enabled) |
| Gemini model | `${OCC_RECOMMENDATION_GEMINI_MODEL:gemini-3.1-flash-lite}` |
| Gemini temperature | `${OCC_RECOMMENDATION_GEMINI_TEMPERATURE:1.0}` |
| Gemini output limit | `${OCC_RECOMMENDATION_GEMINI_MAX_OUTPUT_TOKENS:512}` |
| Gemini thinking level | `${OCC_RECOMMENDATION_GEMINI_THINKING_LEVEL:MINIMAL}` |
| Gemini request timeout | `${OCC_RECOMMENDATION_GEMINI_REQUEST_TIMEOUT:30s}` |
| Gemini total HTTP attempts | `${OCC_RECOMMENDATION_GEMINI_RETRY_ATTEMPTS:2}` |
| Maximum HTTP request body | `${OCC_RECOMMENDATION_MAX_REQUEST_BODY_BYTES:65536}` bytes |
| Maximum serialized model prompt | `${OCC_RECOMMENDATION_MAX_PROMPT_INPUT_BYTES:65536}` UTF-8 bytes |
| Database URL | `${OCC_RECOMMENDATION_DB_URL:jdbc:postgresql://localhost:5432/occ}` |
| Database username | `${OCC_RECOMMENDATION_DB_USERNAME:occ_recommendation}` |
| Database password | `OCC_RECOMMENDATION_DB_PASSWORD` (required secret) |
| Flyway schema | `recommendation` |
| Health endpoints | `health`, `info` |

These variables are inventoried in the repository-root `.env.example`. The
Maven and IDE `local` profile loads the root `.env` from the service's nested
working directory. Compose supplies the same settings through its environment.

## Persistence status

Database ownership is confirmed as:

| Database | Schema | Owner/login role |
|---|---|---|
| `occ` | `recommendation` | `occ_recommendation` |

`infra/local/postgres/init/04_recommendation_schema.sh` creates the role and
schema, revokes public schema access, sets the role's search path to
`recommendation, public`, and sets its timezone to UTC. The script is safe to
rerun against an existing local volume and does not reset data or change an
existing role's password.

The application connects as `occ_recommendation`. Flyway migration
`V001__create_recommendation_record.sql` creates the singular
`recommendation_record` table and supporting indexes. `V004__compact_recommendation_record.sql`
contracts it to nine columns: the recommendation/idempotency UUID, request hash,
indexed event and safety projections, immutable request/context/response JSON,
and creation time. Each successful action retains:

- the SHA-256 fingerprint of the validated API request;
- the complete validated request as JSON;
- the fixture-backed mitigation context and applied constraints as JSON;
- the complete validated recommendation as JSON;
- the generated evidence IDs, rationale, status and creation time inside the
  immutable JSON payloads.

`recommendation_id` is the idempotency key and the primary key, so `INSERT ...
ON CONFLICT DO NOTHING` makes concurrent duplicate handling database-backed.
Other services must
integrate through this API instead of reading or mutating the schema directly.

Flyway migration `V002__create_model_invocation_record.sql` creates a separate
`model_invocation_record` table linked to `recommendation_record`. It records:

- provider, model, provider response ID, prompt version and output-schema
  version;
- invocation start/completion times and latency;
- prompt, completion and total token counts when the provider reports them;
- the structured candidate when one was produced;
- final outcome: `ACCEPTED`, `REJECTED`, `MISMATCHED`, `EMPTY_RESPONSE` or
  `PROVIDER_FAILURE`;
- deterministic rejection reason or sanitized provider-failure category when
  applicable; and
- whether the deterministic fallback recommendation was used.

The table intentionally does not retain the API key, rendered prompt, raw
provider response, provider error body or stack trace. The stored operational
input remains in `recommendation_record`; model audit references it through the
recommendation ID instead of duplicating it.

The recommendation and its optional model audit are written in the same
transaction. Idempotent replay returns before candidate generation, so it does
not invoke Gemini or create another audit row. Under simultaneous first-time
requests with the same idempotency key, both requests may reach the provider;
only the invocation associated with the winning stored recommendation is
retained in this minimal scope.

### Local database verification

With Docker Desktop running and a private
`OCC_RECOMMENDATION_DB_PASSWORD` in the ignored repository-root `.env`, the
local Compose stack was provisioned on PostgreSQL port `5432`. The schema-init
job was run more than once to confirm that it can safely provision an existing
database volume. The following properties were verified:

- database: `occ`;
- schema: `recommendation`;
- schema owner and login: `occ_recommendation`;
- `occ_recommendation` is not a PostgreSQL superuser and cannot create roles or
  databases;
- the role's default search path is `recommendation, public` and timezone is
  UTC;
- `PUBLIC` has no privileges on the `recommendation` schema; and
- authenticated connections as `occ_recommendation` can create and roll back a
  temporary table in its own schema.

The earlier temporary probe table was rolled back. The application-owned tables
are `recommendation_record` and `model_invocation_record`, both created through
Flyway rather than the role/schema initialization script.

The documented Route 190 request was also verified against the local Compose
service: the initial call created one record, an identical retry returned the
same response, different content with the same key returned `409`, and the
stored response remained available after restarting the application container.

### Live Gemini verification

The guarded provider flow was verified on 2026-09-26 through the local Compose
service with a private development key and `gemini-3.7-flash` before the repository-wide model standard was changed to `gemini-3.1-flash-lite`:

- Gemini produced the exact structured `HOLD` candidate for bus `SBS5898U` at
  stop `44241` for 120 seconds;
- deterministic validation accepted the candidate and did not use fallback;
- the persisted audit recorded `ACCEPTED`, model and version information,
  5,430 ms provider latency, and usage of 641 prompt, 123 completion and 925
  total tokens;
- replaying the same idempotency key returned the stored response in 127 ms,
  leaving exactly one recommendation and one model-invocation row; and
- the private key, rendered prompt, raw response and provider error body were
  not persisted or printed.

The verification also exercised failure behavior. The retired
`gemini-2.5-flash` model returned `404`, and two `gemini-3.8-flash` attempts
returned transient `503` responses after retries. In every case the service
returned the unchanged deterministic recommendation and persisted a sanitized
provider-failure audit. That historical run verified `gemini-3.7-flash`; the current configured default is `gemini-3.1-flash-lite` and requires its own deployment smoke check.
The active Gemini 3 configuration uses the `MINIMAL` thinking level (not the
legacy thinking-budget parameter) and excludes thought content. The current
3.1 Flash Lite default still requires its own deployment smoke check.

## WQAP-3 integration status

WQAP-3 should call this service after it has produced a diagnosis for a
confirmed bunching event. The current request fixture is the executable
consumer example until both services share a finalized cross-service contract.

### WQAP-3 caller checklist

The WQAP-3 implementation must:

1. Preserve the event received from the detection/simulation flow and attach
   its diagnosis under the `diagnosis` object. The recommendation engine cannot
   reconstruct missing event fields from a diagnosis ID.
2. Send `POST /internal/v1/bunching-recommendations` with
   `Content-Type: application/json` and a UUID `Idempotency-Key` header.
3. Generate or persist one idempotency UUID for the intended recommendation
   request. Every retry must reuse the same key and the same validated request
   content, including the same `requestId`; different content under the same key
   returns `409 Conflict`.
4. Supply stable UUIDs for `requestId`, `bunchingEventId`, and `diagnosisId`,
   stable evidence references, and an ISO-8601 UTC timestamp such as
   `2026-09-27T23:45:02Z` for `firstConfirmedAt`.
5. Send `classification: SIMULATION` and event `status: CONFIRMED` for the
   current actionable demo flow. `OBSERVATION_ONLY` deliberately returns `422`.
6. Treat `200` as one recommendation, `400` as a contract/validation fault,
   `409` as incorrect idempotency-key reuse, and `422` as a valid but
   non-actionable request. The caller must not retry `400`, `409`, or `422`
   unchanged.
7. Retry timeouts or transient `5xx` responses according to WQAP-3's bounded
   retry policy while retaining the same idempotency key and payload.
8. Use the API response rather than querying the `recommendation` schema. The
   schema and its `occ_recommendation` role are private to this service.

The exact request fields and validation meaning are documented under
**API contract** above. In particular, the diagnosis supplied by WQAP-3 must
contain:

| Field | WQAP-3 responsibility |
|---|---|
| `diagnosisId` | Stable UUID for the produced diagnosis. |
| `likelyCause` | Non-blank root-cause conclusion, at most 1,000 characters. |
| `confidence` | Numeric value from `0.0` through `1.0`. |
| `evidenceIds` | One or more non-blank stable evidence references. |
| `limitations` | An array of known qualifications; it may be empty. |

WQAP-3 must also forward the complete `bunchingEvent` object shown in the
example request. `observedHeadwaySeconds` requires between 2 and 10 values, and
the engine evaluates the latest value.

### Network configuration for WQAP-3

Use the URL appropriate to where WQAP-3 runs:

| WQAP-3 execution location | Recommendation-engine base URL |
|---|---|
| Host-native process or manual host test | `http://localhost:6769` when the engine runs in Compose |
| Container in the same Compose project | `http://recommendation-engine:8003` |
| Shared environment | Deployment-supplied internal service URL |

When WQAP-3 adds its client setting, expose it through a specifically named
environment variable such as `OCC_RECOMMENDATION_INTERNAL_URL`, bind it through
typed configuration, and add it to the root `.env.example`. Containers must use
service DNS and internal port `8003`, never the host-published port `6769`.

No service-to-service authentication is implemented for this internal endpoint
yet. If the team introduces authentication, update both services and the
OpenAPI contract together.

### Integration verification

From the repository root, a teammate can start the complete deterministic
recommendation path with:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml up -d --build `
  postgres recommendation-schema-init recommendation-engine
```

Verify health and then submit the maintained fixture:

```powershell
Invoke-RestMethod http://localhost:6769/actuator/health

$body = Get-Content `
  services/planning/recommendation-engine/src/test/resources/fixtures/requests/confirmed-bunching-recommendation.json `
  -Raw

$idempotencyKey = [guid]::NewGuid().ToString()
$first = Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:6769/internal/v1/bunching-recommendations `
  -ContentType application/json `
  -Headers @{ "Idempotency-Key" = $idempotencyKey } `
  -Body $body

$replay = Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:6769/internal/v1/bunching-recommendations `
  -ContentType application/json `
  -Headers @{ "Idempotency-Key" = $idempotencyKey } `
  -Body $body

$first.recommendationId -eq $replay.recommendationId
```

Expected results are health status `UP`, a `200` response containing exactly
one recommendation, and `True` for the replay comparison. The maintained Route
190 fixture currently produces a `HOLD` recommendation for `SBS5898U` at stop
`44241` for 120 seconds. With AI disabled this verifies the complete
deterministic, database and idempotency path without a billable provider call.

Finally, run the complete automated suite from the service directory:

```powershell
./mvnw.cmd clean verify
```

Docker Desktop must be running because the integration tests use PostgreSQL in
a container. The automated suite does not require a Gemini API key and does not
make live Gemini calls.

## Phase 7 boundary

Gemini/Spring AI and model-call audit persistence are integrated for Phase 7,
but model output must not bypass the deterministic safety policy. The boundary
is:

1. Gemini proposes structured mitigation candidates or explanation content.
2. Schema validation rejects malformed model output.
3. Deterministic code applies classification, crowd-safety, operational and
   adjustment limits.
4. The service returns only the best validated recommendation.
5. Model, prompt/schema version, latency, usage, evidence and validation outcome
   are persisted for audit and replay.

Durable request/recommendation and model-call audit persistence are implemented,
and the guarded provider flow has been verified with a live private development
key without weakening deterministic fallback behavior.

Phase 7 Steps 1 through 7 are implemented:

- `MitigationCandidateGeneratorPort` defines a provider-neutral generation
  boundary using current event, diagnosis and validated mitigation context;
- `MitigationCandidate` permits only `HOLD` or `DWELL_ADJUSTMENT`, an identified
  bus and control stop, an adjustment from 1 through 120 seconds, and a bounded
  rationale;
- `BunchingMitigationCandidateValidator` checks simulation classification,
  confirmed status, operational validation, crowd safety, approved bus and
  stop, action compatibility, positive headway deficit, and the lowest
  applicable adjustment limit;
- rejected candidates carry a stable machine-readable reason for later
  fallback handling and audit persistence;
- the deterministic policy runs first as an eligibility and safety preflight,
  so non-actionable input never invokes a candidate provider;
- when candidate generation is enabled and a provider is available, the
  orchestrator validates its output and uses its bounded rationale only when
  the proposed type, bus, stop and adjustment exactly match the deterministic
  action and its fixture-backed impact;
- disabled generation, a missing provider, empty output, rejected or mismatched
  candidates, and provider failures all return the unchanged deterministic
  recommendation;
- `GeminiMitigationCandidateAdapter` sends only the current event, diagnosis and
  validated context through Spring AI and requests one provider-native
  JSON-Schema-constrained candidate;
- Gemini is registered only when `OCC_RECOMMENDATION_AI_ENABLED=true`; its API
  key, model, temperature, output limit, timeout and retry attempts are bound
  through typed configuration, and the key is redacted from configuration text;
- Gemini 3 uses the configurable `MINIMAL` thinking level and excludes thought
  content so bounded structured JSON is produced reliably;
- recommendation IDs, statuses, classifications, impact estimates, safety
  decisions and evidence remain owned by deterministic code;
- model identity, versioned prompt/schema, response ID, latency, optional usage,
  candidate, validation outcome and fallback decision are persisted without
  raw prompts, raw responses or secrets; and
- `OCC_RECOMMENDATION_AI_ENABLED` defaults to `false`.

The live flow now contains both guarded orchestration and the Gemini adapter.
With the flag disabled, HTTP behavior remains fully deterministic. With the flag
enabled and a valid key, Gemini proposes a structured candidate; malformed,
unsafe or mismatched output and provider failures fall back to the deterministic
recommendation. Phase 7 is complete for the current WQAP-4 scope; WQAP-3
service-to-service integration remains separate follow-up work.

## Guidance for contributors and AI agents

- Follow the repository root `AGENTS.md` and `docs/CONVENTIONS.md`.
- Treat this README and the generated OpenAPI document as the current service
  contract; keep both synchronized with API changes.
- Preserve the `OBSERVATION_ONLY` safety boundary.
- Do not invent operational facts, bus identifiers or impact measurements.
- Keep model generation separate from deterministic validation.
- Add or update unit and integration tests whenever policy or API behavior
  changes.
- Run `./mvnw.cmd clean verify` before committing on Windows, or
  `./mvnw clean verify` on Unix-like systems.
- Do not commit `target/`, credentials, local `.env` files or model secrets.

## Related repository references

- [`docs/plans/sprint-1/sprint-1.md`](../../../docs/plans/sprint-1/sprint-1.md)
- [`docs/CONVENTIONS.md`](../../../docs/CONVENTIONS.md)
- [`SimulatedData/README.md`](../../../SimulatedData/README.md)
- [`SimulatedData/route190/bunching/scenario.json`](../../../SimulatedData/route190/bunching/scenario.json)
- [`SimulatedData/route190/bunching_mitigated/scenario.json`](../../../SimulatedData/route190/bunching_mitigated/scenario.json)
