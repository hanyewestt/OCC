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

A separate, single read-only request to the configured LTA `BusServices` endpoint used `DATAMALL_API_KEY` from `.env` and returned HTTP 200 with a valid 500-row JSON page. This verifies that credential and endpoint; the application workflow above still used its provider stub. After confirming the configured environment variable, one synthetic live Gemini smoke request used `OCC_PLANNING_GEMINI_API_KEY` and model `gemini-3.1-flash-lite`; it returned HTTP 200 in 4.8 seconds and valid structured JSON with `causeCode=UNKNOWN`. The smoke sent no real operational or personal data and verifies only provider connectivity and response shape. The Planning application remains configured with `OCC_PLANNING_RECOMMENDATION_AI_ENABLED=false`, so its workflow still uses deterministic fixtures and has not been exercised against live Gemini. See [live Supabase results](phase1-supabase-live-auth.md).
