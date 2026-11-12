# Testing and CI

Run commands from the repository root. Docker must be running for database
integration tests. The root Maven Wrapper pins Maven 3.9.16; host runs need JDK 25.

## Automatic and extended checks

[`Core CI`](../.github/workflows/ci.yml) runs on every pull-request update,
pushes to `main`, and manual dispatch. Feature-branch pushes do not start a
second copy of the PR checks. Its independent jobs cover:

| Job | Coverage |
| --- | --- |
| Launcher and setup checks | Bash and PowerShell startup, reset/clear, pgAdmin actions and failure handling on Windows and Linux, using fake Docker/npm commands. |
| Reactor tests and migration checks | All three Java services, unit/slice tests, database integration tests and contract export; no skipped tests allowed. |
| Frontend checks | Reproducible install, lint, TypeScript and production build. |
| Configuration and API contract checks | Example Compose configurations, OpenAPI validation and API compatibility baselines. |
| Live API contract comparison | Running service definitions match the committed OpenAPI files. |
| Nginx and authentication smoke | HTTP/HTTPS routes to all three services, assets, 401/403 role rules, hidden endpoints, rate limits, SSE streaming and static hosting with a backend stopped. |
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

## Commit triggers and the deployment path

Commit classification follows [commit convention](commit-convention.md):
classify by behavior, not file extension. Deployment-affecting commits —
workflows, Dockerfiles and build tooling, runtime Compose/Nginx/CloudFront
configuration, database setup/migrations and CD/lifecycle scripts — always run
CI/CD on `main`, even with no service or frontend source edits.

Commits that are exclusively documentation, agent prose or local launchers
keep the `[skip ci]` marker. GitHub suppresses push workflows for those
commits; there is no privileged bypass. If a marker is used by mistake on a
deployment-affecting commit, manually dispatch the verification workflow for
that commit. If a docs-only skipped run leaves required checks pending on a
pull request, add a normal non-skipped commit or validate manually according
to branch protection.

### Branch protection migration for the CD pipeline

Pushes to `main` no longer run `Core CI` directly: they run
**Main verification**, which invokes the same reusable verification once for
the exact pushed SHA. Pull requests continue to run `Core CI` with unchanged
job names. In branch protection:

- Pull-request required checks keep their existing `Core CI` names (including
  the two system caller jobs' `System verification` checks).
- Required checks for pushes to `main`, if your protection rules target them,
  move from the `Core CI` names to the same names under the
  `Main verification` caller workflow. Workflow files do not change
  branch-protection settings automatically; update the rules in the
  repository settings when adopting the CD pipeline.
- The **Release and deploy** workflow runs after every successful Main
  verification on `main`. Its publish, scan and deploy jobs use the
  `production` environment (main-only, no per-push reviewers) and the
  deployment role secrets; pull-request jobs never see them. Its mutation
  runs are serialized in one concurrency group and are never cancelled.

The path-filtered Extended verification workflow still builds from source.
Core CI additionally runs `Candidate workflows and recovery / System
verification` against the complete candidate images. Main verification must
finish that check before CD can publish. This adds a required-check name;
inspect one actual PR and main run before changing branch protection.

The packaging job uses the main-only `OCC_VERIFY_ROLE_ARN` repository variable
for read-only published-baseline/ECR access. Pull requests build all five
images without assuming production roles. `occ-candidate` freezes the producing
run, SHA, owner plan, input hashes, reused sources and OCI/config/archive
digests. The post-verification pipeline publishes built OCI bytes with
`--preserve-digests`, verifies all final ECR digests, then scans and produces an
SBOM for each exact final digest. Failed, skipped or incomplete scan jobs block
publication. Main's manual `refresh` is `none`, `rescan` or `rebuild`.

Infrastructure checks use `cfn-lint infra/aws/ec2-cd.yaml` and
`cfn-guard validate -d infra/aws/ec2-cd.yaml -r infra/aws/ec2-cd.guard`.
Run the outbound-boundary mutation cases with
`cfn-guard test -r infra/aws/ec2-cd.guard -t tests/cd/egress.guard-tests.yaml`.

CD tests run on Linux; Windows also executes portable policy tests and the real
PowerShell bootstrap CLI fixture. `python tests/cd/host_integration.py all`
requires Docker and fails when it is unavailable. It exercises real isolated
PostGIS backup/restore (spatial data, roles, ownership and grants) and Buildx
OCI transfer/import, Linux lock exclusion, and terminal-failure quiescing of real
containers while preserving PostgreSQL. Its S3 boundary is a local fixture; AWS IAM, EC2 boot,
systemd, CloudFront and provider connectivity require a reviewed live rollout.

Publication has its own queue. Deploy, pause and resume share
`occ-production-mutation`, `cancel-in-progress: false`, `queue: max`; status is
outside it. GitHub permits 100 pending entries; overflow/cancelled jobs must be
retried explicitly. Durable S3 operation ownership still applies after runner
loss. Actionlint 1.7.12 has no `queue` support: ignore only that exact diagnostic
when running it, and check the parsed concurrency contracts separately.

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

The reactor covers Ingestion, Disruption and Planning. Use isolated
Compose projects for disposable system tests and preserve normal development
database volumes. On a memory-constrained engine, stop the development stack
while running another complete application stack; restart it afterward.
For the same zero-skips run as Core CI, add `-Docc.exportOpenapi=true` to
include the opt-in Ingestion contract export test (quote the flag in PowerShell).

Planning regression tests cover concurrent on-demand triggers, persisted manual
queue requests, retries and expired-lease recovery with automatic bunching off.
The trigger returns lifecycle status immediately; provider calls run in the
worker, which selects the appropriate bunching or hazard processor. Completed
requests, including traffic/hazard requests without a diagnosis row, are replayed
without changing their outcome.

## React authentication app

Browser E2E tests do not run automatically on PRs or pushes to main. Run them
locally with `npm test`, or use **Actions → Core CI → Run workflow** to run the
HTTPS browser journeys manually. The Nginx/auth smoke job still checks HTTP/TLS
routing, static assets and backend authorization on every automatic Core CI run.
The manual-run control is available once the workflow exists on the default branch.

From `frontend/`, run `npm ci`, `npm run lint`, `npm run typecheck`,
`npm run build`, `npx playwright install chromium`, then `npm test`.
The browser journeys mock Supabase and API responses; they prove frontend
behavior and session lifecycle, not live provider or backend authorization.
Token-refresh journeys verify that same-account refresh preserves an unsaved
notice draft and that a verified role change removes the prior role's content.
Ingestion tests independently enforce the controller/captain role matrix. See
[development guide](DEVELOPMENT.md) for Nginx-targeted browser runs and
[authentication](AUTHENTICATION.md) for opt-in live checks.

Captain browser tests also cover pacing thresholds, instruction
acknowledgement, the assigned route, narrow-screen layout and persistent
day/night mode. They verify that the captain screen does not call the
fleet-wide pacing API. Planning and ingestion tests enforce that
captains read and acknowledge only instructions and pacing for the bus assigned
to their verified identity.

Controller browser tests cover dashboard navigation, simulation controls,
bunching arrival evidence and approve/reject controls. These use mocked APIs
and do not record decisions in a real database. Edge checks verify that retired
`/demo.html` and `/auth.js` URLs return 404 and are absent from the build.
Admin browser tests cover verified admin routing, sidebar navigation, account
search/details/synchronization, controller/captain provisioning outcomes, audit
filters and escaped evidence. Mocked
browser tests exercise workflows and do not replace the PostgreSQL concurrency
tests or the isolated Nginx role-matrix smoke checks.

## System verification

CI compares Nginx responses byte-for-byte with the React build under `frontend/dist/`,
checks role restrictions and hidden routes through HTTP/TLS, and proves static
pages remain available with a backend stopped. Public APIs route directly to the
owning service; internal, management and API-documentation paths are blocked at Nginx.

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
The image matrix covers all three applications and the combined database-setup
image; the pinned Nginx image has its own scan and SBOM job.

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

## Direct-edge migration regression checks

`python tests/system/nginx_proxy_smoke.py` starts one disposable, hardened Nginx
container with isolated echo upstreams. It checks destination selection, URI and
query rewriting, HTTP methods, bearer/idempotency header forwarding, generated
correlation IDs and removal of spoofed peer/Forwarded headers. It exercises the
actual route template; it does not prove backend authorization. The edge smoke
uses real backend filters and synthetic signed JWTs and checks both pacing SSE
URLs, including the Ingestion-prefixed alias.

Every automatic edge-smoke run also launches Chromium for `startup.spec.ts`
against the built HTTPS site. This verifies that production JavaScript actually
renders sign-in without an uncaught exception; asset-byte checks and successful
Vite compilation alone cannot establish that. The full browser journeys remain
available through the documented manual workflow and local Playwright commands.

Public notice regression checks: the anonymous board requests `classification=ALL` and displays both live and simulation notices with scope labels, including when opened through an older simulation preview URL. Explicit API scope filters remain supported. Rejected AI notice wording returns source facts for controller review and retains the rejection audit.
