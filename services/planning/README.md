# OCC Planning

Planning owns diagnosis and validated mitigation recommendations in one Spring
Boot service. Disruption submits work to `/internal/v1/planning/requests`; the
diagnosis worker gathers evidence and calls the recommendation domain directly
inside this process. There is no diagnosis-to-recommendation HTTP call.

Planning accepts versioned `BUS_BUNCHING` and `TRAFFIC_INCIDENT` submissions from
Disruption. Bunching requests follow the existing diagnosis and safe
recommendation workflow. Traffic lifecycle snapshots are retained as immutable
request evidence and finish with `policyOutcome=TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION`;
phase 1 deliberately defines no traffic intervention policy.

The public API uses `/api/v1/planning/...` and accepts Supabase JWTs with an
`occ_role` claim of `ADMIN` or `CONTROLLER`. The internal request endpoint only
accepts the configured `X-OCC-Peer-Token` from Disruption. Captain and unassigned
users cannot use public Planning routes. The token is a per-peer capability,
not a Supabase user token; keep its value in the ignored root `.env` or a
deployment secret store.

The application's existing Live/Simulation selection also scopes Planning. Planning reads
Disruption's authoritative consumer selection; it has no independent mode toggle.
Confirmed live bunching events carry `OBSERVATION_ONLY` evidence with no demo run ID.
Simulation events carry `SIMULATION` evidence and the active run ID. Captured inputs
keep their original scope through retries and mode changes.

`GET /api/v1/planning/diagnoses` returns a paginated list of requests and diagnosis
results for the selected mode (only the active run in Simulation). Provider failures
remain visible. Switching modes does not relabel or delete retained diagnoses.
Live diagnoses finish with `LIVE_DIAGNOSIS_NO_VALIDATED_OPERATIONAL_CONTEXT` and do
not call the simulation mitigation fixture or create operational instructions.
Validated live vehicle assignments, control stops and crowd-safety inputs are still
required before live mitigation decisions can be supported.

Migration V007 expands the request policy-outcome constraint for live advisory
completion. Apply it before enabling live diagnosis workers. It preserves existing
requests and traffic outcomes; an application rollback can leave the expanded
constraint in place without deleting retained live results.

The pending recommendation queue only includes the active simulation run. Decisions
are rejected when their retained diagnosis does not belong to the current scope.
Historical results remain available by ID for review. Recommendation AI enablement
is independent of data selection: `OCC_PLANNING_RECOMMENDATION_AI_ENABLED` only
controls mitigation candidate generation, not the Gemini diagnosis client.

Controllers review actionable simulation recommendations through the pending
queue at `/api/v1/planning/recommendations/pending`. Approving records the AI
recommendation as accepted. Rejecting requires one to three controller-authored
bus instructions and a rationale; both are stored with the authenticated
controller subject. `/api/v1/planning/recommendations/decisions` returns the
persisted decision history, newest first, for the controller audit screen.

The demo checks `/api/v1/planning/requests/by-event/{eventId}` when a recommendation
is not yet available. This authenticated lookup reports pending, retryable and
terminal diagnosis states even if no diagnosis result was created. It defaults
to `BUS_BUNCHING`; `eventType=TRAFFIC_INCIDENT` selects a traffic event. A 404 means
the event has not been accepted by Planning. A terminal failure remains recorded;
close the simulation and start a new one to retry with fresh evidence.
The default Gemini request deadline is 30 seconds. For a slow live provider, set
`OCC_PLANNING_GEMINI_REQUEST_TIMEOUT=60s` in `.env` and recreate Planning to apply
it; no application rebuild is needed. The same deadline applies to diagnosis
and recommendation requests. An exhausted diagnosis retry budget is reported
as a terminal failure in the demo instead of remaining pending indefinitely.

## Local setup and verification

Copy the repository-root `.env.example` to `.env`, then set the Supabase project
URL and JWKS URL, the signing algorithm, the Planning database credentials, and
a generated `OCC_DISRUPTION_TO_PLANNING_PEER_TOKEN`. The same directed token is
configured on Disruption as its Planning caller capability. In Compose, Planning connects to PostgreSQL
using `OCC_PLANNING_DB_INTERNAL_URL`; when running from the service directory
with the `local` profile, it uses `OCC_PLANNING_DB_PUBLIC_URL` and imports the
root `.env`.

To refresh the checked-in OpenAPI contract, start the local Compose stack, put a
short-lived ADMIN or CONTROLLER access token in `OCC_OPENAPI_EXPORT_TOKEN` for the
current shell only, then run from the repository root:

```powershell
$env:OCC_OPENAPI_EXPORT_TOKEN = '<short-lived access token>'
.\mvnw.cmd -pl services/planning -Popenapi org.springdoc:springdoc-openapi-maven-plugin:1.5:generate "-Dopenapi.apiDocsUrl=http://localhost:6769/v3/api-docs"
Remove-Item Env:OCC_OPENAPI_EXPORT_TOKEN
```

This updates `services/planning/openapi/planning.json` from the running API.
Never save the token in `.env` or commit it.
The OpenAPI security identifiers are `SupabaseJWT` and `DisruptionPeerToken`;
these describe the existing bearer/peer-header authentication and do not change
the API routes, role checks or header names.

Start the local database using [the local setup guide](../../docs/LOCAL-DEVELOPMENT.md),
then run Planning from its service directory:

```powershell
Set-Location services/planning
..\..\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

```bash
cd services/planning
../../mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Run its unit and integration checks from the repository root with
`mvnw -pl services/planning verify`, or run the full target reactor with
`mvnw verify`. Integration checks use PostgreSQL Testcontainers; Docker must be
available.

## Runtime settings

The root [`.env.example`](../../.env.example) is the authoritative variable
inventory and gives a safe example for every setting. Planning-specific values:

| Variables | Purpose |
| --- | --- |
| `OCC_PLANNING_DB_INTERNAL_URL`, `OCC_PLANNING_DB_PUBLIC_URL`, `OCC_PLANNING_DB_USERNAME`, `OCC_PLANNING_DB_PASSWORD` | PostgreSQL connection for Compose and host-native runs. |
| `OCC_PLANNING_INTERNAL_PORT`, `OCC_DISRUPTION_TO_PLANNING_PEER_TOKEN`, `OCC_PLANNING_TO_DISRUPTION_PEER_TOKEN` | Listener port and the two directed Planning/Disruption service capabilities. |
| `OCC_PLANNING_RECOMMENDATION_AUDIT_MAX_RESPONSE_CHARACTERS`, `OCC_PLANNING_DIAGNOSIS_AUDIT_MAX_RESPONSE_CHARACTERS` | Bound retained model response text. |
| `OCC_PLANNING_RECOMMENDATION_MAX_PROMPT_INPUT_BYTES`, `OCC_PLANNING_DIAGNOSIS_MAX_PROMPT_INPUT_BYTES`, `OCC_PLANNING_DIAGNOSIS_PEER_RESPONSE_MAX_BYTES` | Bound generated prompts and peer response bodies. |
| `OCC_PLANNING_RECOMMENDATION_AI_ENABLED`, `OCC_PLANNING_RECOMMENDATION_FIXTURE_LOCATION` | Enable Gemini candidate generation or choose the deterministic fixture. |
| `OCC_PLANNING_GEMINI_API_KEY`, `OCC_PLANNING_GEMINI_BASE_URL`, `OCC_PLANNING_GEMINI_MODEL`, `OCC_PLANNING_GEMINI_TEMPERATURE`, `OCC_PLANNING_GEMINI_THINKING_LEVEL`, `OCC_PLANNING_GEMINI_REQUEST_TIMEOUT`, `OCC_PLANNING_GEMINI_RETRY_ATTEMPTS` | Shared Gemini settings for diagnosis and recommendations. Leave `OCC_PLANNING_GEMINI_BASE_URL` blank to use Google's default endpoint; set it only for a compatible Gemini API endpoint such as the local workflow-test stub. Keep AI disabled until a key is configured. |
| `OCC_PLANNING_RECOMMENDATION_GEMINI_MAX_OUTPUT_TOKENS`, `OCC_PLANNING_DIAGNOSIS_GEMINI_MAX_OUTPUT_TOKENS` | Separate output-token limits for each model task. |
| `OCC_PLANNING_DIAGNOSIS_RETENTION_INTERVAL`, `OCC_PLANNING_DIAGNOSIS_RETENTION_INITIAL_DELAY` | Schedule cleanup of short-lived diagnosis request/delivery state; retained AI evidence follows its separate policy. |
| `OCC_PLANNING_DIAGNOSIS_WORKER_ENABLED`, `OCC_PLANNING_DIAGNOSIS_WORKER_INTERVAL`, `OCC_PLANNING_DIAGNOSIS_WORKER_BATCH_SIZE`, `OCC_PLANNING_DIAGNOSIS_LEASE_DURATION`, `OCC_PLANNING_DIAGNOSIS_MAX_ATTEMPTS` | Control Planning's durable diagnosis worker. |
| `OCC_PLANNING_DIAGNOSIS_DELIVERY_MAX_ATTEMPTS`, `OCC_PLANNING_DIAGNOSIS_RETRY_INITIAL_DELAY`, `OCC_PLANNING_DIAGNOSIS_RETRY_MAX_DELAY` | Bound and schedule retries when Planning submits a recommendation record. Zero delivery attempts means retry indefinitely with capped backoff. |
| `OCC_PLANNING_DIAGNOSIS_EVIDENCE_LOOKBACK`, `OCC_PLANNING_DIAGNOSIS_EVIDENCE_RADIUS_KM`, `OCC_PLANNING_DIAGNOSIS_CONFIDENCE_THRESHOLD` | Configure the evidence window and descriptive diagnosis confidence threshold. |
| `OCC_PLANNING_DIAGNOSIS_REQUEST_TIMEOUT`, `OCC_DISRUPTION_INTERNAL_URL`, `OCC_INGESTION_INTERNAL_URL` | Configure internal evidence-service destinations and request timeouts. Compose uses service DNS and internal ports. |
| `OCC_PLANNING_SWAGGER_ENABLED` | Enable or disable Swagger UI and generated API docs. |

JWT settings are shared with Gateway: `SUPABASE_PROJECT_URL`,
`SUPABASE_JWKS_URL`, `SUPABASE_JWT_AUDIENCE`, `SUPABASE_JWT_CLOCK_SKEW`, and
`SUPABASE_JWT_ALLOWED_ALGORITHMS`. The issuer is derived from the project URL;
the role converter trusts only the top-level `occ_role` claim.
