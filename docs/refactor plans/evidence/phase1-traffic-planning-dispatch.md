# Phase 1 traffic lifecycle and Planning dispatch

Implementation status: typed traffic lifecycle submission and the Planning
no-action result are implemented. The database-backed integration tests passed
on 15 October 2026. The generated Planning OpenAPI contract now includes the
typed traffic event fields and response property. On 16 October, an isolated
Compose workflow passed a successful synthetic DataMall poll through Ingestion,
Disruption, the protected Gateway query, and Planning's no-action policy as an
`OBSERVATION_ONLY` event with no demo scope. The feed response was synthetic;
the real LTA response has not yet been exercised through the new application
path.

Disruption creates a versioned `TRAFFIC_INCIDENT` Planning envelope and a
durable outbox row in the same transaction as each `OPENED`, `UPDATED`, or
`CLEARED` event and its evidence. The envelope carries the event and disruption
IDs, lifecycle action, classification/demo scope, source observation and poll
IDs, provider facts, and event time. Existing bunching envelope serialization
omits the new nullable traffic field, preserving the prior
`planning-event-v1` payload shape and fingerprint for pending bunching outbox
rows.

Planning validates the typed traffic snapshot and its scope, persists the exact
producer facts, and returns `COMPLETED` with
`policyOutcome=TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION`. This event path does not
call the bunching diagnosis/context pipeline, Gemini, or the bunching
recommendation policy. Bunching submissions keep the existing workflow.

Latest verification run:

```text
$env:TESTCONTAINERS_RYUK_DISABLED='true'
.\mvnw.cmd --batch-mode --no-transfer-progress -pl services/ingestion verify
.\mvnw.cmd --batch-mode --no-transfer-progress -rf :disruption verify
BUILD SUCCESS across the module retries; 213 Surefire tests and 57 Failsafe
integration tests passed, with zero failures, errors, or skips. The configured
Ingestion and Disruption JaCoCo coverage gates passed; Planning and Gateway
have no configured coverage thresholds.
```

This run included PostgreSQL-backed lifecycle, persistence and receiver
integration tests. `TrafficIncidentLifecycleIT` passed all four tests after
fixing two test assumptions: PostgreSQL `TIMESTAMPTZ` precision is microseconds,
and live facts use `OBSERVATION_ONLY` classification while demo facts use
`SIMULATION`. No integration-test suite was skipped in this successful run.

The earlier full bunching workflow smoke passed through the local Gateway with
a real Supabase ADMIN token and synthetic provider overrides. The 16 October
`.env` run separately passed the bunching simulation through Planning with one
valid live Gemini diagnosis and a fixture-backed recommendation. That same
simulation emitted `TRAFFIC_INCIDENT` `OPENED` and `UPDATED` events in
`SIMULATION` scope; both reached Planning and completed with
`TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION`. Ingestion also continued to poll real
DataMall data: 3,540 persisted HTTP 200 polls covered 5,216 provider records,
and all 118 feed targets were healthy. Those runs did not exercise an actual
DataMall traffic incident through the new lifecycle; the synthetic provider
workflow below verifies the same adapter-to-Planning path without claiming a
real incident was observed. Synthetic-auth Nginx assertions are recorded
separately as well.

The checked-in generated Planning OpenAPI contract was exported from the
running secured Planning service after the traffic fields were added. It
contains `TRAFFIC_INCIDENT`, `trafficSnapshot`, and the `policyOutcome`
response field. Duplicate delivery after receiver commit, restart recovery at
each documented crash point, capacity/soak, and hosted CI remain open; do not
mark A07 complete from the current unit, integration, or bunching workflow
evidence alone.

## Lost-response retry test

On 15 October 2026, `BunchingDiagnosisDispatcherIT.retryAfterReceiverCommitAndLostResponseReusesTheSameRequest` passed in the Docker-backed Disruption integration suite. Its HTTP receiver harness records one stable request ID under the incoming idempotency key, then drops the first response after that record is made. The dispatcher leaves the outbox pending, retries the same payload/key/fingerprint, and marks it delivered on attempt two; the receiver harness contains exactly one record. The existing `DiagnosisRequestPersistenceIT` independently verifies stable database replay for the Planning receiver. Together these cover sender retry and receiver idempotency, but the receiver in the lost-response test is a test harness, not the Planning application. Full multi-container receiver-commit fault injection and restart-at-each-crash-point checks remain open.

The Java fault proxy also passed its host-only smoke on 15 October. After Docker became available, the multi-container recovery smoke passed against the isolated Compose project `occ-dispatch-20261015`: Planning committed the request before the proxy dropped HTTP 202, then Planning and Disruption restarted. The outbox remained durable and was either pending its retry or already delivered on attempt two by the time the check ran. In both cases, the proxy observed the same idempotency key, fingerprint, and body; Planning retained exactly one durable request, the recommendation remained queryable after recovery, and the simulation closed successfully. The smoke now accepts both valid retry timing states while checking the same durability and idempotency invariants.

This closes the injected receiver-commit/lost-response/restart scenario only. Restart-at-each-other-documented-crash-point coverage, actual LTA traffic through the new application path, capacity/soak, hosted CI, and cloud deployment remain open; do not mark A07 complete from this one recovery scenario.

The first repeat on 16 October in isolated Compose project `occ-phase1-recovery-20261016` became healthy and created a simulation event, then timed out after 20 seconds during its read-only Compose/psql outbox query. It stopped before the recovery assertions and was not counted as a pass. `docker info` reported server 29.1.3; a later Docker `exec` returned Engine API HTTP 500 while `com.docker.service` was stopped.

After the container API recovered, I reset only that isolated test project and its disposable volume, rebuilt it from the current four-service images, and reran the smoke with a fresh synthetic ADMIN token/JWKS. The runner confirmed the event and pending outbox were committed before delivery (`PENDING`, attempt 0, no Planning request). Planning accepted one request and the fault proxy dropped its HTTP 202 response. After both services restarted, the outbox stayed retryable with one committed Planning row; the retry reused the original idempotency key, fingerprint, and payload, then delivered exactly once. The recommendation remained queryable with the same ID after a second Planning/Disruption restart, and the simulation closed. The isolated stack used synthetic providers and did not touch the `.env` application database. This passes the injected receiver-commit/lost-response/restart and committed-result restart scenario. Other documented crash points and live-provider traffic-incident lifecycle through Compose remain open, along with capacity/soak and hosted CI.

## 16 October 2026 isolated Compose workflow

Using project `occ-phase1-traffic-20261016`, `.env.example`, an ephemeral
ES256/JWKS issuer, and the synthetic provider in
`tests/system/provider_stubs.conf`, the updated four-service images and local
Nginx TLS edge became healthy. The live OpenAPI export matched the committed
Ingestion, Disruption, and Planning contracts. The traffic smoke found the
`OBSERVATION_ONLY` incident through Gateway and verified a successful
`TRAFFIC_DATAMALL` poll, a normalized Ingestion observation, and a completed
Planning row with
`TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION`; it had no demo-run ID.

The existing bunching recovery smoke also passed in that stack. Its fault proxy
now arms for the target bunching outbox idempotency key and counts only matching
submissions, so concurrent simulation traffic events cannot consume the
injected failure. Planning committed before HTTP 202 was dropped; after Planning
and Disruption restarted, the same key, fingerprint, and payload were retried,
one Planning request remained, and the recommendation stayed queryable after a
second restart. The smoke closed its simulation. All 19 TLS edge assertions
passed, including ADMIN/CONTROLLER allow, DRIVER/UNASSIGNED deny, path hiding,
forged peer-token rejection, and rate limiting (45 unauthorized requests and
55 rate-limited requests in the burst check).

A 4-VU, 15-second k6 polling check completed 52 requests and 104 response
checks with 0% request failures and 100% checks passed; p95 request latency was
233.3 ms against the 1,000 ms target. This short local smoke does not establish
representative-host capacity or retention soak. A real LTA traffic incident was
not present in this stubbed run. Hosted CI, other documented crash points,
representative-host capacity/soak, and deployment remain open.

The Windows host hardware queried on 16 October was an Intel Core Ultra 7 155H
with 16 physical cores, 22 logical processors, and 15.4 GiB of memory, running
Windows 11 build 26200. Docker Desktop/WSL CPU and memory limits were not
captured. This records the local smoke-test context; it does not establish
representative deployment capacity or close the load/retention-soak gate.

## 16 October real LTA provider contract check

A read-only request to the real LTA DataMall Traffic Incidents endpoint using
the existing `DATAMALL_API_KEY` returned HTTP 200 with 43 records in the
`value` array and the documented `Type`, `Latitude`, `Longitude`, and
`Message` fields. No response payload or credential was retained. LTA's current
[DataMall API User Guide v6.10, section 2.18](https://datamall.lta.gov.sg/content/dam/datamall/datasets/LTA_DataMall_API_User_Guide.pdf?ref=public_apis)
documents the two-minute update frequency, the 500-record response limit, and
`$skip` paging. The Ingestion adapter now follows that paging contract and
marks a snapshot partial when its configured page budget cannot establish a
complete feed. This direct provider check verified the live schema only; the
updated application path could not be exercised while the Docker engine was
returning HTTP 500. A real incident through Ingestion, Disruption, and Planning
remains open.

## 16 October real LTA application path and restart verification

After unused-container cleanup and the successful 290-test reactor run, all
seven app/migration images were rebuilt and the real `.env` stack was recreated
against its retained volume. This supersedes the earlier Docker blocker for
the live incident path. Ingestion's runtime source URL was verified as the
real HTTPS `datamall2.mytransport.sg` endpoint, with no provider override.

The initial post-rebuild snapshot contained one successful `TRAFFIC_DATAMALL`
poll, 29 normalized `OBSERVATION_ONLY` observations and 29 active Disruption
incident states. An administrative verification query correlated each of 29
completed Planning requests to a real raw/normalized observation, successful
poll, Disruption event evidence and delivered outbox. All 29 had
`TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION`; the two older simulation requests
were excluded. No model invocation was associated with these live incidents.
These cross-schema queries are verification-only administrative access, not
runtime service joins or grants.

A real Supabase ADMIN session read the selected incident through
`/api/v1/disruption/traffic-incidents` and its completed request through
`/api/v1/planning/requests/{requestId}` on Gateway. After restarting Planning
and Disruption, both became healthy, the same request ID and no-action policy
remained queryable, and exactly one Planning request existed for that event.
The test session signed out. No real Gemini request was made by this check.

The local opt-in runner is kept outside the project at
`../OCC-verification/live_traffic_workflow_smoke.mjs` (relative to the repository root),
as requested by the owner. It is not a repository/CI dependency:

```powershell
node --env-file=.env ../OCC-verification/live_traffic_workflow_smoke.mjs occ-phase1-live-auth-20261015 http://localhost:6770 --restart
```

Use the running project's actual name and public Gateway URL, and supply the
documented publishable key/ADMIN test-account settings to its process. The
saved runner itself passed, including restart and sign-out. It retains tokens
only in memory, validates the live provider source, and does not run in ordinary
CI. Without `--restart` it performs the read/correlation checks only.

This closes the previous real LTA incident-through-application gap and proves
the persisted observation-only result survives a restart. It does not establish
every process-crash point, real-provider update/clear behavior, representative
capacity/retention soak, hosted CI or production deployment. Existing synthetic
and database tests cover the deterministic open/update/clear and incomplete-feed
cases; keep those separate from this live-provider evidence.
