# OCC Planning

Planning owns diagnosis and validated mitigation recommendations in one Spring
Boot service. Disruption submits work to `/internal/v1/planning/requests`; the
diagnosis worker gathers evidence and calls the recommendation domain directly
inside this process. There is no diagnosis-to-recommendation HTTP call.

The public API uses `/api/v1/planning/...` and accepts Supabase JWTs with an
`occ_role` claim of `ADMIN` or `CONTROLLER`. The internal request endpoint only
accepts the configured `X-OCC-Peer-Token` from Disruption. Driver and unassigned
users cannot use public Planning routes. The token is a per-peer capability,
not a Supabase user token; keep its value in the ignored root `.env` or a
deployment secret store.

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
| `OCC_PLANNING_GEMINI_API_KEY`, `OCC_PLANNING_GEMINI_MODEL`, `OCC_PLANNING_GEMINI_TEMPERATURE`, `OCC_PLANNING_GEMINI_THINKING_LEVEL`, `OCC_PLANNING_GEMINI_REQUEST_TIMEOUT`, `OCC_PLANNING_GEMINI_RETRY_ATTEMPTS` | Shared Gemini settings for diagnosis and recommendations. Keep AI disabled until a key is configured. |
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
