# Testing and CI

Run commands from the repository root. Docker must be running for database
integration tests. The root Maven Wrapper pins Maven 3.9.16; host runs need JDK 25.

## Java tests

PowerShell:

```powershell
.\mvnw.cmd verify
```

Bash:

```bash
./mvnw verify
```

The reactor covers Ingestion, Disruption, Planning and Gateway. Use isolated
Compose projects for disposable system tests and preserve normal development
database volumes. On a memory-constrained engine, stop the development stack
while running another complete application stack; restart it afterward.

## Browser authentication helper

This command works in PowerShell and Bash:

```shell
node --test tests/system/frontend_auth.test.mjs
```

It checks token handling and session boundaries with controlled responses.
It does not prove a live Supabase or Gemini connection.

## System verification

CI compares Nginx responses byte-for-byte with the files under `frontend/static/`,
checks role restrictions and hidden routes through HTTP/TLS, and proves static
pages remain available with Gateway stopped. Public APIs route through Gateway;
internal, management and API-documentation paths are blocked at Nginx.

Contract fixtures live in `tests/contracts/`; generated service contracts live
under `services/<service>/openapi/`. CI validates compatibility and compares
running service output with those files. CI has no dependency on ignored
planning documents under `docs/`.

The system runners under `tests/system/` require their documented isolated
stack, issuer, provider fixtures and tokens. They are not standalone commands
against an arbitrary live app. The CI workflow defines their complete setup.
Keep generated credentials and ad hoc live-run artifacts outside the checkout.
See [authentication](AUTHENTICATION.md) for live Supabase checks and session
limitations. Distinguish actual providers, synthetic evidence, stubbed responses
and disabled model branches in every result; ordinary CI uses deterministic
fixtures and must not rely on paid provider calls.

## Load, security and recovery checks

CI runs [`important_api_performance.js`](../tests/system/important_api_performance.js)
with the pinned Grafana k6 `2.3.0` image and a short synthetic ADMIN-token profile.
It measures the authenticated demo-mode, arrivals, live alerts, active-run and
run-status, route, stop, demo recommendation, direct Disruption alert, and
direct Planning recommendation lookup endpoints through the TLS edge. CI also
reports the response time for starting and closing a demo run, and how long the
simulation and asynchronous recommendation take to become ready. For the k6 GET
endpoints, p95 must stay below 2 seconds; status, response shape, and request
errors are checked too. Comparing the composite reads with their direct
service endpoints gives a rough view of service-hop overhead, but does not
replace tracing or database profiling. The workflow uses fast synthetic
providers, so its readiness time is not a measurement of live Gemini or
DataMall latency. Safe profile settings are listed at the bottom of
`.env.example`; never put a real Supabase token there. This is a small
regression smoke, not a capacity or retention-soak result. Extended capacity
qualification on a representative deployment host remains deferred for the
MVP.

The repository secret/misconfiguration job sets the CI-tool-only
`TRIVY_OFFLINE_SCAN=true` to prevent unrelated remote Maven dependency lookups.
It still scans source files and enforces HIGH/CRITICAL findings through the
sanitizer. Separate image vulnerability jobs use current vulnerability databases
and retain their HIGH/CRITICAL failure gates and SBOM output.
The image matrix covers all four applications and all three schema-migration
images; the pinned Nginx image has its own scan and SBOM job.

The isolated CI workflow also routes Disruption-to-Planning submissions through
[`compose.dispatch-test.yaml`](../infra/compose.dispatch-test.yaml). Its
test-only proxy drops the first response after Planning returns HTTP 202, then
the smoke restarts both services and verifies the same idempotency key and one
durable Planning request survive. The control port is loopback-only and the
profile must use its own Compose project and database volume.
