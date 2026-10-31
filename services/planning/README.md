# OCC Planning

Planning owns diagnosis and validated mitigation recommendations in one Spring
Boot service. Disruption submits work to `/internal/v1/planning/requests`; the
diagnosis worker gathers evidence and calls the recommendation domain directly
inside this process. There is no diagnosis-to-recommendation HTTP call.

Planning accepts versioned `BUS_BUNCHING`, `HAZARD` and `TRAFFIC_INCIDENT`
submissions from Disruption. Bunching requests follow the diagnosis and safe
recommendation workflow. Hazard and traffic lifecycle events retain their source
facts and can produce controller-reviewed proposals for exposed buses in the
active simulation run. They do not issue instructions automatically. Live data
remains observational: there is no live-bus assignment or live OBU mitigation
workflow.

Controller and admin APIs use `/api/v1/planning/...` and require a Supabase JWT
with an `occ_role` claim of `ADMIN` or `CONTROLLER`. Captains can read only
instructions assigned to their verified subject and acknowledge those
instructions; unassigned users cannot access private Planning APIs. Public
notice reads at `/api/v1/planning/public/notices` are anonymous. The internal
request endpoint only accepts the configured `X-OCC-Peer-Token` from Disruption.
That token is a per-peer capability, not a Supabase user token; keep its value
in the ignored root `.env` or a deployment secret store.

The application's existing Live/Simulation selection also scopes Planning. Planning reads
Disruption's authoritative consumer selection; it has no independent mode toggle.
Confirmed live bunching events carry `OBSERVATION_ONLY` evidence with no demo run ID.
Simulation events carry `SIMULATION` evidence and the active run ID. Captured inputs
keep their original scope through retries and mode changes.

`GET /api/v1/planning/diagnoses` returns paginated bunching requests and diagnosis
results for the selected mode (only the active run in Simulation). Summaries use
validated incident evidence and keep uncertain causality explicit; bounded source
facts and limitations are returned separately for the controller's evidence
disclosure. Existing retained diagnoses are presented from stored snapshots without
rerunning AI or changing decisions. Provider failures remain visible.
`GET /api/v1/planning/diagnoses/hazard-requests` lists the latest
unfinished hazard request per disruption in that same scope, including pending,
retrying and terminal failures. This endpoint remains available to operational clients;
failed processing never creates an instruction. The controller Audit log uses
`GET /api/v1/planning/diagnoses/hazard-history` for the latest terminal request
per disruption in the selected scope. Only `COMPLETED` and `FAILED_TERMINAL`
requests appear; pending work, processing and retryable failures are excluded.
Results are ordered by terminal `completedAt` newest first, with page default 0
and size default 50 (1–100). A later pending request does not hide an earlier
terminal result. This is a bounded operational view under existing retention,
not a permanent processing trail. The original `hazard-requests` endpoint keeps
its unfinished-work behavior. Switching modes does not relabel
or delete retained diagnoses.
Live diagnoses finish with `LIVE_DIAGNOSIS_NO_VALIDATED_OPERATIONAL_CONTEXT` and do
not call the simulation mitigation fixture or create operational instructions.
Validated live vehicle assignments, control stops and crowd-safety inputs are still
required before live mitigation decisions can be supported.

Migration V007 expands the request policy-outcome constraint for live advisory
completion. Apply it before enabling live diagnosis workers. It preserves existing
requests and traffic outcomes; an application rollback can leave the expanded
constraint in place without deleting retained live results.

Migration V012 restores non-null legacy score columns for application rollback.
Evidence-only diagnoses store zero compatibility values in those columns and set
`confidence_scores_available=false`; this release reads those rows as having no
scores. The preceding release can read them without null dereferences. Keep the
migrated database when rolling back; do not reverse Flyway migrations.

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

The controller can check `/api/v1/planning/requests/by-event/{eventId}` when work
is not yet complete. This authenticated lookup reports pending, retryable and
terminal states even if no diagnosis result was created. It defaults to
`BUS_BUNCHING`; `eventType=HAZARD` or `eventType=TRAFFIC_INCIDENT` selects a hazard
event. A 404 means Planning has not accepted that event. A later source or bus
exposure event can queue fresh hazard work; a terminal failure never becomes an
automatic OBU instruction.
The default Gemini request deadline is 30 seconds. For a slow live provider, set
`OCC_PLANNING_GEMINI_REQUEST_TIMEOUT: 60s` in Planning's `environment` through
an additional Compose file and recreate Planning to apply it; host-native local
runs can set `OCC_PLANNING_GEMINI_REQUEST_TIMEOUT=60s` in `.env`. No application
rebuild is needed. The same deadline applies to diagnosis
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

The root [`.env.example`](../../.env.example) inventories supported deployment variables and secrets:

| Deployment Variables | Purpose |
| --- | --- |
| `OCC_PLANNING_DB_INTERNAL_URL`, `OCC_PLANNING_DB_PUBLIC_URL`, `OCC_PLANNING_DB_USERNAME`, `OCC_PLANNING_DB_PASSWORD` | PostgreSQL connection for Compose and host-native runs. |
| `OCC_PLANNING_INTERNAL_PORT`, `OCC_DISRUPTION_TO_PLANNING_PEER_TOKEN`, `OCC_PLANNING_TO_DISRUPTION_PEER_TOKEN` | Listener port and the two directed Planning/Disruption service capabilities. |
| `OCC_PLANNING_RECOMMENDATION_AI_ENABLED`, `OCC_PLANNING_RECOMMENDATION_FIXTURE_LOCATION` | Enable Gemini candidate generation or choose the deterministic fixture. |
| `OCC_PLANNING_GEMINI_API_KEY`, `OCC_PLANNING_GEMINI_BASE_URL`, `OCC_PLANNING_GEMINI_MODEL` | Gemini credentials and model selection. Leave `OCC_PLANNING_GEMINI_BASE_URL` blank to use Google's default endpoint. |

Operational timings, AI sampling parameters, worker loops, prompt byte caps, and advisory bounds have safe canonical defaults in `application.yml` and can be overridden via `OCC_*` environment variables when needed:

- Advisory bounds (`occ.planning.advisory.max-speed-reduction-kmh: 10`, `max-duration-seconds: 900`, `max-subsequent-stops: 10`, `acknowledgement-window: 5m`, `retention: 12h`).
- AI generation & audit caps (`gemini.temperature: 1.0`, `thinking-level: MINIMAL`, `max-output-tokens: 512/1024`, prompt input max bytes: 65,536, audit response max chars: 65,536).
- Diagnosis worker & retries (`worker-interval: 2s`, `batch-size: 10`, `lease-duration: 5m`, `max-attempts: 3`, `evidence-lookback: 15m`, `evidence-radius-km: 2.0`).

Existing aliases such as `OCC_PLANNING_DIAGNOSIS_WORKER_ENABLED` and
`OCC_PLANNING_GEMINI_REQUEST_TIMEOUT` remain supported. Canonical names use the
actual property path, for example `OCC_DIAGNOSIS_WORKERENABLED`. Inject optional
Compose overrides into Planning's `environment` with an additional Compose file;
the base stack no longer forwards tuning values from the root `.env`.

### Captain instructions and public notices

`/api/v1/planning/advisory-groups` exposes the pending controller review queue,
grouped by hazard evidence revision, service, direction, action and exact
instruction parameters before pagination. Each group includes its complete
reviewed bus list. Controllers submit all group proposal IDs to
`POST /api/v1/planning/advisory-groups/decisions` to send once to those recipients.
The transaction revalidates every bus's route, downstream exposure, assignment
and movement conflicts; one invalid recipient rolls back all deliveries. A
changed recipient list requires another review. Identical retries return the
original individual instructions, with independent captain acknowledgements,
expiry and cancellation. Broadcasts do not subscribe buses that arrive later.
The existing `/api/v1/planning/advisories` endpoints remain available for
individual review, overrides and conflict resolution. Controllers preview the
exact OBU message, then issue an approved instruction through the
same Planning transaction that persists the decision. Existing typed HOLD and
DWELL recommendations keep their operational validation and have a dedicated
approve-and-issue action. Captains read only instructions assigned to their
verified JWT subject from `/api/v1/planning/captain/instructions`; an
acknowledgement stores the verified subject and server time. It records receipt,
not physical execution. Operational instruction and proposal rows use the
configured short retention window. Model input/output, the original candidate,
and the controller's final decision are copied into
`planning.advisory_model_evidence`, which follows the existing long-lived AI
evidence policy and is not removed by operational advisory cleanup. Operational
advisory and diagnosis rows use a 12-hour default retention and have separate
Planning-specific environment settings. A scheduled
reconciliation cancels instructions from closed demo runs, including stop-based
instructions whose acknowledgement alone has no expiry; the next operational
cleanup then removes their expired lifecycle rows.
Issue and acknowledgement counters use only the bounded instruction-type label;
they do not expose bus, captain or instruction identifiers as metric labels.

Public notice drafts and publication history are controller-only at
`/api/v1/planning/notices`. Anonymous reads use
`/api/v1/planning/public/notices`; the default `OBSERVATION_ONLY` scope excludes
simulation notices. A simulation board requires an explicit `demoRunId` and is
labelled as demonstration content. Published notices are immutable; corrections
publish a replacement and withdraw the previous notice in one transaction.
Corrections must be effective immediately (`effectiveFrom` at or before server
time) and unexpired, so replacement never creates a gap in public visibility.
Identical correction retries return the stored correction even after expiry,
withdrawal or source changes; they cannot republish it. Different content under
the same identity or a competing correction returns `409`. Publishing and
withdrawing retain their original timestamps on retries; withdrawn notices
cannot be published again.
Lifecycle timestamps use PostgreSQL microsecond precision so the first response
and subsequent reads/retries return the same instant.
Controllers can request source-fact or configured Gemini wording suggestions at
`POST /api/v1/planning/notices/{disruptionId}/suggestions`. Suggestions only
populate editable text and affected services/locations; they are never saved or
published by that request. AI receives no bus, captain, account, or run IDs,
must preserve a source restriction, and can select only supplied services and
locations. Cleared sources are rejected.

JWT settings are shared with Gateway: `SUPABASE_PROJECT_URL`,
`SUPABASE_JWKS_URL`, `SUPABASE_JWT_AUDIENCE`, `SUPABASE_JWT_CLOCK_SKEW`, and
`SUPABASE_JWT_ALLOWED_ALGORITHMS`. The issuer is derived from the project URL;
the role converter trusts only the top-level `occ_role` claim.
