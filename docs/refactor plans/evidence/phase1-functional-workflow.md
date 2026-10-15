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

This verifies the normal deterministic simulation-to-recommendation path with provider doubles. It does not verify live DataMall or Gemini behavior, the separate live traffic-incident detection lifecycle, duplicate/crash recovery, load or soak, hosted GitHub Actions, or a cloud deployment. Those remain separate Phase 1 acceptance gates.
