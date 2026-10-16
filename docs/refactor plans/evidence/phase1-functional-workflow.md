# Phase 1 deterministic existing-workflow verification

Status: local end-to-end workflow passed on 15 October 2026. The CI edge job now runs the same workflow smoke test with synthetic JWKS, DataMall and Gemini responses.

The test used the isolated Compose project `occ-phase1-demo-e2e`, the normal stack, `infra/compose.test.yaml`, and `infra/compose.workflow-test.yaml`. It created a disposable PostGIS volume and synthetic reference topology with two stops, one Route 190 service and two route-stop rows. A local provider stub served the public JWKS, those DataMall rows and a deterministic diagnosis response. No production `.env`, DataMall key, Gemini key, or real provider request was used. The test certificate, signing key and role tokens were temporary fixtures.

The black-box command was:

```text
python -u tests/system/demo_workflow_smoke.py --base-url https://127.0.0.1:18443 --token-dir <temporary-fixture-directory>
```

It started a run through the TLS edge using an ADMIN token, replayed three simulation steps, and received one `SIMULATION` `OPENED` bunching event for service 190 at stop 44251 with a 45-second observed headway. Disruption delivered one durable Planning request. Planning retrieved its context through the internal Ingestion API, processed one synthetic Gemini `UNKNOWN` diagnosis response, generated the deterministic fixture-backed `RECOMMENDED` HOLD at control stop 44241, and delivered the stored recommendation. Ingestion returned that recommendation through its existing read-only endpoint with the expected impact record. The test then closed the run and returned Disruption to live-feed selection.

Observed run ID: `c0a00034-d25a-4d21-b557-bf07da643bb3`. Observed event ID: `cc44cb50-8ab2-45cd-aabb-b720d0849719`. Observed recommendation ID: `9bf7dd00-05bf-429f-ae72-3aa5bd0adb38`. The synthetic recommendation had status `RECOMMENDED`, mitigation `HOLD`, control stop `44241`, and a bounded 120-second adjustment.

The full [Nginx edge smoke suite](phase1-nginx-edge-smoke-test.md) also passed against this stack: signed ADMIN and CONTROLLER access succeeded, DRIVER and UNASSIGNED requests were forbidden, a modified ES256 signature was rejected, internal routes stayed hidden, and the 100-request burst produced both 401 and 429 responses. The exact limiter split varies by timing.

This verifies the normal deterministic simulation-to-recommendation path with provider doubles. It does not verify the application workflow against the live DataMall feed or invoke Gemini from the Planning service, nor does it verify the separate live traffic-incident detection lifecycle, duplicate/crash recovery, load or soak, hosted GitHub Actions, or a cloud deployment. Those remain separate Phase 1 acceptance gates.

## Additional development `.env` verification

On 15 October 2026, the isolated Compose stack also completed a bunching workflow through the local Gateway using a real development Supabase ADMIN token. Run `c1ee4a19-c004-4c33-92ba-8dcc8b301259` closed successfully; event `fdd73b9f-e331-4e11-95fe-01b9234918e1` produced stored recommendation `1e80568a-7a63-4855-8ea4-835ba3bc953e`. The Supabase URL/JWKS configuration came from the user's local `.env`; no secret values or tokens were written to evidence. This run went through Gateway directly, not through Nginx TLS.

`infra/compose.workflow-test.yaml` deliberately replaced DataMall and Gemini with synthetic providers for this repeatable end-to-end run. Recommendation AI was disabled in the user's `.env`, so the stored recommendation came from the configured deterministic fixture. This proves real Supabase authentication integrated with the local workflow, not live model output.

A separate, single read-only request to the configured LTA `BusServices` endpoint used `DATAMALL_API_KEY` from `.env` and returned HTTP 200 with a valid 500-row JSON page. An earlier synthetic live Gemini probe used `OCC_PLANNING_GEMINI_API_KEY` and model `gemini-3.1-flash-lite`; it returned HTTP 200 in 4.8 seconds and valid structured JSON with `causeCode=UNKNOWN`. Those standalone probes verified provider connectivity and response shape only. The later 16 October integrated run below exercised real DataMall polling in Ingestion and one synthetic Gemini diagnosis through Planning. Recommendation AI remained disabled, so its result continued to use the deterministic fixture. See [live Supabase results](phase1-supabase-live-auth.md).

On 15 October 2026, the local `.env` Compose stack was recreated after the Docker engine became available. The existing PostgreSQL volume was preserved. Bootstrap reconciled all configured runtime and migrator passwords, all three Flyway migration jobs exited successfully, and PostgreSQL plus Ingestion, Disruption, Planning, and Gateway became healthy. Ingestion startup imported the configured DataMall topology: 5,211 bus stops, 602 services, and 26,835 route-stop rows. All 118 polling targets reported `HEALTHY` with zero consecutive failures; the database contained 248 raw observations and 500 normalized arrivals. The real DataMall importer therefore ran successfully inside Spring, beyond the earlier direct endpoint checks.

The live Supabase smoke used the project URL and JWKS URL from the root `.env`, with the publishable key and temporary test-account credentials supplied only as process environment for this run. ADMIN and CONTROLLER signed in, had their ES256 tokens validated, refreshed successfully, and received HTTP 200 through the local Gateway. DRIVER signed in and refreshed successfully and received HTTP 403. All three sessions signed out. A repeat on 16 October also attempted UNASSIGNED; Supabase returned HTTP 400 before issuing a session, so its live authorization outcome remains unverified. No token, password, or key was saved to this evidence; see [the detailed live Supabase results](phase1-supabase-live-auth.md).

## Live-provider application verification

On 16 October 2026 (Singapore time), the local `.env` stack was verified with the actual configured `DATAMALL_API_KEY` and `OCC_PLANNING_GEMINI_API_KEY`. Planning and Disruption were recreated from the `.env` values, which enabled the diagnosis worker and Planning dispatcher; the `.env` file itself was not changed. `OCC_PLANNING_RECOMMENDATION_AI_ENABLED` remained `false`, so this run could exercise one live diagnosis without enabling Gemini recommendation generation. Before the test, the Planning queue had no nonterminal requests and the Disruption outbox had no undelivered events.

The authenticated `tests/system/demo_workflow_smoke.py` run completed through Gateway. It created a deterministic simulation, produced one bunching event, and retrieved the stored recommendation after Planning processed it. PostgreSQL records exactly one `GOOGLE_GENAI` diagnosis invocation for the new request: model `gemini-3.1-flash-lite`, attempt 1, outcome `VALID`; Planning marked the request `COMPLETED`. The recommendation remained on the configured fixture path. Only synthetic simulation evidence was sent to Gemini. Supabase sign-out returned HTTP 204 after the run. The temporary ADMIN JWT file remains in the OS temporary directory because local file cleanup was blocked by the filesystem safety policy; its 01:01 Singapore-time expiry has passed, so manually remove the stale file before continuing.

At 16 October 00:02 Singapore time, Ingestion had 3,540 persisted DataMall poll runs with HTTP 200 and `SUCCESS` outcome, covering 5,216 provider records in total. All 118 live feed targets reported `HEALTHY`, with zero consecutive failures. The scheduled application poller was running with the actual `DATAMALL_API_KEY`; the `.env` file was not changed and the existing database volume was preserved.

The simulation also produced synthetic traffic-incident lifecycle events, and Planning completed them with `TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION`. That run verified the simulation-scoped traffic submission path. A later 16 October rebuilt-app check additionally correlated 29 real LTA incidents through Ingestion, Disruption and completed Planning no-action outcomes, read a selected result through Gateway with a real Supabase ADMIN session, and verified the same request after Planning/Disruption restart. See [live Supabase results](phase1-supabase-live-auth.md) and [traffic dispatch evidence](phase1-traffic-planning-dispatch.md). Other documented crash points, representative-host load/soak, hosted CI, cloud deployment, and the UNASSIGNED live-auth path remain open.

After the image refresh, the app containers were recreated from the newly scanned images while preserving the database volume. Gateway, Ingestion, Disruption, and Planning all returned healthy status. An unauthenticated protected Gateway request returned HTTP 401. The process-only runtime overrides kept diagnosis dispatch and recommendation AI disabled, while Ingestion continued using the real `DATAMALL_API_KEY`; PostgreSQL recorded 472 successful DataMall HTTP 200 poll runs in the five minutes after Ingestion restarted. No Gemini request was made during this post-refresh check.

## 16 October extended local crash verification

The local opt-in runner and its artifacts live outside the checkout in the
sibling `OCC-verification` directory. Run from the repository root:

```powershell
python -u ../OCC-verification/continue_phase1.py .
```

The successful fault checks used isolated Compose project
`occ-phase1-remaining-64dc11bd`, synthetic ES256/JWKS and provider fixtures,
and the current locally built application images. The live `.env` project was
paused to avoid running duplicate application stacks. No real Gemini call was
made. The runner explicitly starts a container after SIGKILL; it does not assume
that a manual Docker kill triggers automatic restart. Cursor assertions stay
within one selected demo feed, before demo closure selects the live feed.

| Boundary | Result | Synthetic model calls for selected event |
| --- | --- | ---: |
| Committed detection/outbox, then Disruption restart | Pending outbox and consumer cursor preserved | Not yet dispatched |
| Sender failure before receiver acceptance, then Disruption kill/restart and Planning return | Original outbox accepted as one durable request; one queryable recommendation | 1 |
| Committed Planning claim, before model response | Expired lease reclaimed; same request, one diagnosis and recommendation | 2 |
| Model response received, result transaction blocked, then Planning kill/restart | Uncommitted result recovered with one diagnosis and recommendation | 2 |
| Diagnosis committed, recommendation insert blocked, then Planning kill/restart | Same diagnosis ID reused; one recommendation, no repeated diagnosis call | 1 |

Each completed bunching case asserted exactly one request for its source event,
one diagnosis for its request, and one recommendation for its bunching event.
The four selected requests, in the order above excluding the initial restart
check, were `f01cd83f-8fc6-44cf-8b9b-22f47d2bdabf`,
`0480f8b6-8d20-4867-8578-c870f0da537a`,
`ccf67250-0e25-4500-acba-5ac2fc36960a`, and
`13d73386-03f7-4d5f-ab5c-d1e078dbceee`. Their diagnosis attempt counts were
1, 2, 2, and 1; all four recommendation jobs reached `DELIVERED`.
Synthetic proxy call counts describe observed provider requests, including
calls lost across crashes; they do not imply that every interrupted call left
a committed audit row.

Scheduled Ingestion retention also removed an isolated expired orphan poll
with a two-minute retention window and one-minute cleanup interval. The raw
log, request/diagnosis/job correlations and generated fixtures are outside the
checkout under
`../OCC-verification/remaining-run-20261016/occ-phase1-remaining-64dc11bd/`.
These checks extend the earlier lost-response and traffic lifecycle evidence;
they do not establish AWS host capacity or hosted CI execution.

The subsequent local load/retention profile passed: 20 virtual users for ten
minutes issued 11,880 authorized `/api/v1/demo-mode` reads through Nginx TLS and
Gateway (19.80 requests/second). All 23,760 status/shape checks passed, with
zero HTTP failures. Average latency was 9.47 ms, p95 15.93 ms and maximum
37.41 ms, passing the configured p95 <500 ms and error-rate <1% thresholds.
The final Planning model-audit count was unchanged (four `DIAGNOSIS`/`VALID`
records), and no requests remained `PENDING`, `PROCESSING` or
`FAILED_RETRYABLE`. Independent retained-outcome reads during the soak verified
all four original requests, diagnoses and recommendations through Gateway/TLS.

Docker Engine 29.1.3 reported 22 CPUs and 4,103,536,640 bytes of container
memory (3.82 GiB). Nineteen samples spanning seconds 0-578 recorded aggregate
container memory of 1,990.36-2,123.51 MiB, including PostgreSQL, four services,
edge/provider/model helpers and k6 when running. A late-run state snapshot
reported all five service/database containers healthy and none OOM-killed.
This is a short local read-heavy baseline, not a long-duration leak proof,
write-heavy stress test or representative AWS-host capacity result.

The external artifact directory contains `k6.log`, `memory-samples.json`,
`performance-summary.json`, `recovery-correlations.txt`,
`retained-api-outcomes.json`, `retained-api-recommendations.json`, and the
health snapshot. The opt-in runner keeps generated fixtures outside Git and
tears down its isolated containers before restoring the real `.env` project;
named database volumes are preserved.

## Final hosted and post-relocation checks

[Hosted run 38851208234](https://github.com/hanyewestt/OCC/actions/runs/38851208234)
passed all 12 jobs at source `9d6bd8f`, including 290 reactor tests, exact runtime
OpenAPI comparison, the demo-to-recommendation workflow, traffic lifecycle,
receiver-commit/restart recovery, k6 and Nginx role/routing/security checks.
See [the hosted result](reactor-build.md) for counts and profile measurements.

After relocating Disruption resources to `db/migration` and rebuilding its
application/migration images, the real `.env` stack was restored and all five
app/database containers became healthy. The migration job exited 0 with an
identical before/after Flyway history. The external live read helper correlated
a real LTA raw observation, successful poll, normalized incident, event
evidence, delivered outbox and completed Planning no-action request. A real
Supabase ADMIN session then read the incident and terminal outcome through
Gateway and signed out. The first read exceeded its 20-second client deadline;
the retry with the existing configurable 60-second test deadline passed without
changing application settings. Direct JWKS probes from Gateway and Disruption
both returned 200. This read-only verification made no Gemini request and did
not modify `.env`; logs remain in `../OCC-verification/`.
