# Testing and CI

Run commands from the repository root. Docker must be running for database
integration tests. The root Maven Wrapper pins Maven 3.9.16; host runs need JDK 25.

## Automatic and extended checks

[`Core CI`](../.github/workflows/ci.yml) runs on every pull-request update,
pushes to `main`, and manual dispatch. Feature-branch pushes do not start a
second copy of the PR checks. Its independent jobs cover:

| Job | Coverage |
| --- | --- |
| Reactor tests and migration checks | All four Java services, unit/slice tests, database integration tests and contract export; no skipped tests allowed. |
| Frontend checks | Reproducible install, lint, TypeScript, build and mocked browser authentication tests. |
| Configuration and API contract checks | Example Compose configurations, OpenAPI validation and API compatibility baselines. |
| Live API contract comparison | Running service definitions match the committed OpenAPI files. |
| Nginx and authentication smoke | HTTP/HTTPS routes, assets, 401/403 role rules, hidden endpoints, rate limits, browser journeys and static hosting with Gateway stopped. |
| Security scans | Repository secrets/configuration and all application, migration and Nginx images. |

These jobs do not depend on one another. A contract failure does not prevent
the routing/authentication job from running. Within a job, later steps still
stop after a failure; security scan reports and disposable-stack cleanup use
`always()` where needed.

[`Extended verification`](../.github/workflows/extended-verification.yml) runs
on PRs changing `services/`, `infra/`, root Compose files, Maven build files,
system tests, contract fixtures or CI workflows. It also supports manual runs
for frontend-only changes and release preparation. It covers traffic incident
workflows, receiver-commit/restart recovery, bunching simulation, recommendation
readiness and the short k6 performance profile. These scenarios use synthetic
providers and an isolated database; they do not call live Supabase or paid AI.

To run it manually, open **Actions → Extended verification → Run workflow**
and select the branch. GitHub requires a dispatchable workflow to exist on the
default branch before its manual-run control is available. No workflow pushes
code or deploys the app.

All system jobs reuse
[`system-verification.yml`](../.github/workflows/system-verification.yml).
Only the extended suite enables provider-backed workers and the fault proxy.
The reusable workflow has no push/PR trigger of its own.

In branch protection, require the Core CI checks (including the two system
caller jobs' `System verification` checks); do not require the path-filtered
Extended verification workflow on every PR. Update any rule referring to the
old `Nginx public-edge black-box checks` name. Workflow files do not change
GitHub branch-protection settings automatically.

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
For the same zero-skips run as Core CI, add `-Docc.exportOpenapi=true` to
include the opt-in Gateway contract export test (quote the flag in PowerShell).

## React authentication app

From `frontend/`, run `npm ci`, `npm run lint`, `npm run typecheck`,
`npm run build`, `npx playwright install chromium`, then `npm test`.
The browser journeys mock Supabase and Gateway; they prove frontend behavior
and session lifecycle, not live provider or backend authorization. Gateway tests
independently enforce the controller/captain role matrix. See
[development guide](DEVELOPMENT.md) for Nginx-targeted browser runs and
[authentication](AUTHENTICATION.md) for opt-in live checks.

Captain browser tests also cover pacing thresholds, timed sample updates,
scenario switching, narrow-screen layout and persistent day/night mode. They
verify that pacing makes no operational API requests. The React pacing tests
are included in the frontend browser suite.

Controller browser tests cover dashboard navigation, simulation controls,
bunching arrival evidence and approve/reject controls. These use mocked APIs
and do not record decisions in a real database. Edge checks verify that retired
`/demo.html` and `/auth.js` URLs return 404 and are absent from the build.

## System verification

CI compares Nginx responses byte-for-byte with the React build under `frontend/dist/`,
checks role restrictions and hidden routes through HTTP/TLS, and proves static
pages remain available with Gateway stopped. Public APIs route through Gateway;
internal, management and API-documentation paths are blocked at Nginx.

Contract fixtures live in `tests/contracts/`; generated service contracts live
under `services/<service>/openapi/`. CI validates compatibility and compares
running service output with those files. CI has no dependency on ignored
planning documents under `docs/`.

The system runners under `tests/system/` require their documented isolated
stack, issuer, provider fixtures and tokens. They are not standalone commands
against an arbitrary live app. The reusable system workflow defines their setup.
Keep generated credentials and ad hoc live-run artifacts outside the checkout.
See [authentication](AUTHENTICATION.md) for live Supabase checks and session
limitations. Distinguish actual providers, synthetic evidence, stubbed responses
and disabled model branches in every result; ordinary CI uses deterministic
fixtures and must not rely on paid provider calls.

## Load, security and recovery checks

Extended verification runs [`important_api_performance.js`](../tests/system/important_api_performance.js)
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

The edge and CI fixtures use the same digest-pinned official Nginx Alpine slim
image. OCC does not use the optional modules bundled in the full Alpine image;
the slim variant excludes their vulnerable `libexpat` and `libx11` dependencies.
When refreshing the image, update every Nginx reference in `compose.yaml` and
`.github/workflows/` together, then run the HIGH/CRITICAL image scan and
HTTP/TLS edge checks. Keep the severity gate and SBOM generation enabled.

The extended workflow also routes Disruption-to-Planning submissions through
[`compose.dispatch-test.yaml`](../infra/compose.dispatch-test.yaml). Its
test-only proxy drops the first response after Planning returns HTTP 202, then
the smoke restarts both services and verifies the same idempotency key and one
durable Planning request survive. The control port is loopback-only and the
profile must use its own Compose project and database volume.
