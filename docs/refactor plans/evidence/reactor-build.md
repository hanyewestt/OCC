# Phase 1 reactor build evidence

Date: 15 October 2026, local Asia/Singapore. Source checkout: `fix/mvp-refactor`.

The final four-service verification used Java 25.0.4. A first clean reactor attempt encountered a transient Testcontainers/Ryuk host-port reset, which skipped database tests and caused the Disruption coverage gate to fail. After retrying Disruption with Ryuk enabled and Ingestion with the documented local Ryuk-disabled setting, the final Surefire/Failsafe reports for all four service targets show 270 tests with zero failures, errors, or skips. The successful module commands were:

```powershell
$env:TESTCONTAINERS_RYUK_DISABLED='true'
.\mvnw.cmd --batch-mode --no-transfer-progress -pl services/ingestion verify
.\mvnw.cmd --batch-mode --no-transfer-progress -rf :disruption verify
```

Both commands completed with `BUILD SUCCESS`. All four phase-one services compiled and packaged. The configured Ingestion and Disruption JaCoCo gates passed; Planning and Gateway do not currently have configured coverage thresholds. The local Ryuk override was limited to the Ingestion test command; project and CI defaults are unchanged. Final Surefire and Failsafe reports for the four target services contain:

| Service | Unit/slice | PostgreSQL integration | Total |
| --- | ---: | ---: | ---: |
| Ingestion | 58 | 11 | 69 |
| Disruption | 59 | 25 | 84 |
| Planning | 78 | 21 | 99 |
| Gateway | 18 | 0 | 18 |
| **Total** | **213** | **57** | **270** |

After that reactor run, the Ingestion feed-sequence gauge query was aligned with the consumer's durable feed criteria, including both stop-scope formats and successful poll-run status. The focused follow-up command `.\mvnw.cmd --batch-mode --no-transfer-progress -pl services/ingestion '-Dit.test=IngestionStartupIT' verify` passed all 56 Ingestion unit/slice tests and all 6 selected `IngestionStartupIT` tests; its Ingestion JaCoCo gate passed.

Database integration tests ran against disposable Testcontainers PostgreSQL/PostGIS instances. They exercised Ingestion migrations and privilege boundaries, legacy bunching import into Disruption, traffic incident lifecycle/outbox persistence, legacy Planning imports, Planning request/recommendation persistence, and receiver/idempotency workflows. `BunchingDiagnosisDispatcherIT` includes a lost-response case: the receiver harness records the first request before the response is dropped, and the retry uses the same idempotency key and payload; the outbox is delivered on attempt two. This is not a multi-container Planning receiver commit/restart fault-injection test. Ingestion tests also verify the isolated test stack disables scheduled live-provider polling and keeps `/demo.html` public while protecting demo control APIs.

All four services now expose `/actuator/prometheus` and authorize it for `ADMIN` only. Integration tests verify unauthenticated access is rejected and an ADMIN token can read the metrics; Gateway tests additionally verify a CONTROLLER token is forbidden. The metrics include Spring Boot/Micrometer JVM, HTTP, and database-pool measurements where applicable, plus low-cardinality service gauges:

- Ingestion: oldest successful feed age, unhealthy feed count, and latest durable arrival feed sequence.
- Disruption: consumed feed sequence, deferred source count, pending Planning outbox count/age, expired leases, retained delivery-attempt total, and traffic-poll age.
- Planning: pending request/job counts and ages, retained deterministic-fallback count, and retained provider-failure count.
- All three business services: process-lifetime counters for rows removed by their scheduled retention cleanup.

Custom gauges and retention counters use no per-event, user, or provider-payload labels. Nginx denies management paths, Gateway does not proxy backend actuator paths, and production Compose does not publish the backend service ports. An isolated local Compose run used a disposable PostGIS volume, ephemeral ES256/JWKS fixtures, and the same hardened Nginx TLS setup as CI. All 18 edge route/JWT assertions passed; the latest 100-request rate-limit check returned 46 unauthorized and 54 rate-limited responses. A warmed 4-VU, 15-second k6 poll through the TLS edge completed 60 requests with 120/120 response checks passing, 0% request errors, and 94.71 ms p95 against the 1,000 ms threshold. A cold poll immediately after restarting Gateway exceeded that latency threshold at 1.23 s while still returning valid authorized responses; CI runs the authenticated workflow before k6 to warm the JWKS/auth path. Hosted CI has not run this change yet. These metrics and the short smoke profile do not close A13: representative-host capacity/load and retention-soak results and hardware details remain outstanding.

This Maven reactor run uses test fixtures and does not establish live-provider behavior or hosted CI status. The local `.env` contains non-empty `DATAMALL_API_KEY` and `OCC_PLANNING_GEMINI_API_KEY`; no credential values are recorded here. The read-only DataMall request using `DATAMALL_API_KEY` returned HTTP 200 with a valid 500-row page. A separate one-request Gemini smoke using `OCC_PLANNING_GEMINI_API_KEY` and synthetic evidence returned HTTP 200 in 4.8 seconds; the JSON response validated with `causeCode=UNKNOWN`. That smoke verified key/model connectivity and structured output only, not the Planning service workflow. `OCC_PLANNING_RECOMMENDATION_AI_ENABLED=false`, so the deterministic end-to-end recommendation verification used its configured fixture. Live auth and provider results are recorded in [functional workflow evidence](phase1-functional-workflow.md) and [Supabase evidence](phase1-supabase-live-auth.md). The separate synthetic Nginx edge checks remain in [edge evidence](phase1-nginx-edge-smoke-test.md).

Compose configuration validation passes with the safe root `.env.example` for both the base stack and production merge. The ignored root `.env` contains distinct development passwords for the three migrator accounts; no values are recorded here. The first `.env`-backed restart exposed that bootstrap created configured passwords only for new roles and did not reconcile existing accounts. `infra/postgres/bootstrap.sql` now rotates the passwords of already-existing runtime and migrator roles after validating their attributes. The regression test seeds six stale passwords and verifies the old values fail and the configured values pass over SCRAM-authenticated TCP. Its three database privilege tests pass. Recreating the local `.env` stack then let all three migration jobs exit successfully and all five application/database services become healthy, with the existing named database volume preserved. Ingestion imported 5,211 stops, 602 services, and 26,835 route-stop records; all 118 provider polling targets were healthy. The live Supabase/Gateway authorization result is recorded in [functional workflow evidence](phase1-functional-workflow.md). The development Compose config also validates against the real `.env`. The production overlay validates with `.env.example`, but real `.env` resolution currently stops on missing `OCC_NGINX_TLS_CERTIFICATE_FILE`; certificate/key paths and immutable service image digests remain deployment inputs.

The initial `clean verify` attempt skipped Testcontainers suites after a Ryuk connection reset. The retry commands above produced current successful reports for every service, with the 11 Ingestion, 25 Disruption and 21 Planning integration tests executed and no skips. The updated Compose merge still passes `docker compose --env-file .env.example ... config -q`, and `git diff --check` passes.

The source configuration inventory check covered all 8 YAML files under active service `src/main/resources` and found 120 unique environment placeholders; all are present in the 179-entry root `.env.example`, with no duplicate inventory names. Safe Compose resolution reports the expected core internal ports. A broader scan encountered ignored generated output from the retired `services/planning/recommendation-engine` and `services/planning/root-cause-diagnosis` trees (173 generated files total). They are not active configuration sources and remain outstanding local cleanup under A15; see [the unused-file review](phase1-unused-files-review.md).

CI contract validation was corrected after replaying its source-to-target operation check. The manifest now maps four retained historical source contracts to their exact paths at baseline revision `0f2cd49`, and CI compares each retained JSON snapshot against that revision before checking operation mappings. The equivalent local check passed for 34 source operations and three target contracts. The live Ingestion, Disruption and Planning OpenAPI documents were exported from running services using a synthetic ADMIN token; all three exactly matched their committed contracts. CI also scans repository secrets/misconfigurations with Trivy and sanitizes the raw report before artifact upload; the sanitizer's seeded HIGH/MEDIUM and value-redaction checks passed. The four-file dispatch recovery Compose merge resolves successfully, and the recovery harness CLI and k6 smoke script pass syntax checks. Workflow YAML parsing, action-SHA checks, `git diff --check` and safe Compose config passed. The local Trivy 0.76.0 filesystem scan passed with no findings; local image scans and SBOMs are summarized below. Hosted CI remains unrun.

## 16 October 2026 dependency and image refresh

The Temurin JDK/JRE base-image pins were updated to `25.0.4.1_1`; the refreshed JRE base's Trivy OS scan reported zero HIGH/CRITICAL findings. The POM overrides Spring Boot's managed libraries with Tomcat 11.0.27, Jackson 2.21.7, and Jackson 3.1.7. The full online `mvnw --batch-mode --no-transfer-progress verify` completed successfully against those versions. The fresh Surefire/Failsafe reports contain 270 tests across all four services, with zero failures, errors, or skips; the Ingestion and Disruption JaCoCo gates passed.

The canonical local Compose override builds images when Compose is run without an explicit `-f compose.yaml`. All four application and three migration images were rebuilt with that merged configuration. An initial Trivy 0.76.0 scan of the Flyway 13.10.0 base found 13 HIGH issues, all inside unused Databricks, Couchbase, or SQL Server drivers. Since this stack migrates PostgreSQL only, `infra/flyway.Dockerfile` now removes those optional drivers while retaining PostgreSQL JDBC. All seven rebuilt images then scanned with zero HIGH/CRITICAL findings using the cached Trivy databases and network disabled. CycloneDX SBOMs were generated for all seven images; local report files remain under the workspace OS temporary directory, while the CI workflow retains its existing seven-day artifact upload. The SBOM contains advisory ratings for multiple Linux distributions, so some individual CVEs show a HIGH rating for other vendors while Ubuntu Noble rates them MEDIUM or LOW. Trivy's distro-selected results for these Ubuntu images are 16 MEDIUM/11 LOW findings per application image and 15 MEDIUM/2 LOW per migration image; the HIGH/CRITICAL gate is zero. The first-rating field in an SBOM is not the severity selected for the image's Ubuntu base.

The three new migration images run as UID 10001 and retain `/flyway/drivers/postgresql-42.7.12.jar`. Read-only Flyway `info` commands succeeded against the live Ingestion, Disruption, and Planning schemas. The four refreshed application images were then used to recreate only the app containers; the existing PostgreSQL container and volume were left running. All four services became healthy, their running image IDs matched the new local tags, and an unauthenticated protected Gateway request returned HTTP 401 with a `WWW-Authenticate` header. Ingestion had 472 recent successful HTTP 200 `DATAMALL` poll runs in the five minutes after its restart. The root `.env` was not changed. Process-only Compose variables kept the Planning diagnosis worker, Disruption dispatcher, and Planning recommendation AI disabled after the single previously approved live Gemini diagnosis; no additional Gemini request was made.

On 16 October, `.\mvnw.cmd --batch-mode --no-transfer-progress test` passed all 213 Surefire tests across Ingestion (58), Disruption (59), Planning (78), and Gateway (18), with zero failures, errors, or skips. This rerun covered unit, slice, architecture, and gateway route/auth tests from the current checkout; it did not run Failsafe/Testcontainers suites. The full `verify` result above remains the evidence for the 270-test Maven build, including database integration suites.

## 16 October 2026 current-working-tree checks

After adding the DataMall traffic-incident poller, paged-provider safety, the
later-page outage regression, and the Gateway JWKS key-rotation test, the root
`.\mvnw.cmd --batch-mode --no-transfer-progress test` passed all 227
Surefire tests: Ingestion 71, Disruption 59, Planning 78, and Gateway 19, with
zero failures, errors, or skips. The rotation test verifies that the same
decoder instance refreshes its JWKS and accepts a token signed by a newly
published key while old and new keys overlap. The focused
`.\mvnw.cmd --batch-mode --no-transfer-progress -pl services/ingestion
'-Dit.test=IngestionStartupIT' verify` previously passed 65 Ingestion unit/slice
tests plus all 7 `IngestionStartupIT` tests against PostgreSQL 18.6/PostGIS
3.6, before the latest pagination settings were added. The current root test
run covers the updated unit/slice configuration; Docker-backed startup tests
were not rerun because the Docker engine returns HTTP 500. The Ingestion
JaCoCo gate passed in the earlier focused verify.

A subsequent root `verify` began on the current source but did not complete.
During `DatabasePrivilegeBoundaryIT`, Docker requests returned Engine API
HTTP 500 and `com.docker.service` was stopped. The elevated attempt to start
that service was canceled, so the Maven run was interrupted before producing a
final verify result. Do not treat that attempt as a pass. The prior full 270-test
verify above predates the final Ingestion poller changes. The current
provider-to-Planning Compose, runtime OpenAPI, lost-response recovery, TLS edge,
and k6 checks did pass; traffic and k6 details are recorded in
[`phase1-traffic-planning-dispatch.md`](phase1-traffic-planning-dispatch.md).

On 16 October, a fresh Docker-independent root `test` rerun again passed 227
Surefire tests with zero failures, errors, or skips. After adding a regression
case for a later DataMall page returning HTTP 503, focused `DataMallAdapterTest`
passed all 8 tests. Compose static resolution passed for the development stack
with both `.env.example` and the real `.env`, and for production, database
integration, workflow, and dispatch-test overlays with `.env.example`. The
resolved local host/container ports are Ingestion 6767/8001, Disruption
6768/8002, Planning 6769/8003, and Gateway 6770/8004. All 120 service-resource
YAML environment placeholders are inventoried; the OpenAPI runtime smoke's
self-test passed its exact-match and route-deletion cases, all current and
retained contract JSON files parse, and `git diff --check` passes.

The current `mvn verify -DskipITs` also completed successfully. All 227
Surefire tests passed, all four service JARs were packaged, and the configured
Ingestion and Disruption JaCoCo checks passed. Failsafe/Testcontainers were
explicitly skipped because the Docker engine was unavailable, so this is
build/coverage evidence, not full integration verification.

The current Docker recheck found `com.docker.service` stopped; `docker info`
timed out through both `desktop-linux` and `default` contexts, and a normal
service start was denied. A loopback request to the Gateway health endpoint
timed out, while an anonymous protected API request returned 401. No
containers or volumes were changed. The live Docker-backed verify and current
application-level DataMall-to-Planning path remain unverified in this shell.

### 16 October 2026 latest bounded-response verification

The DataMall adapter now caps each response stream at the configured decoded
body limit before creating a `String` or parsing JSON. The default is 5,000,000
bytes (`OCC_DATAMALL_MAX_RESPONSE_BYTES`), with typed validation from 1,024 to
20,000,000 bytes. The value is included in `.env.example` and the Ingestion
README. Focused `DataMallAdapterTest` and `DataMallPropertiesTest` passed all
14 tests, including exact-limit acceptance, oversized first-response rejection,
and preservation of earlier traffic rows with an incomplete snapshot when a
later page exceeds the limit.

The latest root `verify -DskipITs` completed successfully with 232 Surefire
tests: Ingestion 75, Disruption 59, Planning 79, and Gateway 19, with zero
failures, errors, or skips. The added Planning regression confirms that
non-simulation classifications do not query the simulation fixture. The build
packaged all four service JARs and passed the configured Ingestion and
Disruption JaCoCo gates. Failsafe/Testcontainers were explicitly skipped, so
this remains build and unit/slice evidence rather than database integration
evidence. The focused DataMall adapter/property run also passed all 14 tests.
Service `src/main/resources/application*.yml` inventory validation found 120
unique environment placeholders, all present in the 180-entry `.env.example`.
Base, test, workflow, and dispatch Compose files all resolved with
`.env.example`; the rendered Ingestion service receives
`OCC_DATAMALL_MAX_RESPONSE_BYTES=5000000`. `git diff --check` passed.
The GitHub Actions workflow YAML parsed with PyYAML, the OpenAPI comparison
self-test detected a removed route, and the Trivy report sanitizer self-test
passed its severity and redaction cases.

The latest Docker recheck from this workspace found `com.docker.service`
stopped. Both Windows Docker contexts return Engine API HTTP 500 for `docker
info`; `docker ps` in the selected `desktop-linux` context also returns HTTP
500. API overrides from 1.44 through 1.51 did not help, and the Ubuntu WSL
Docker client timed out. The Desktop backend owns host ports
6767–6770, but each `/actuator/health` request timed out and PostgreSQL port
5432 was closed. These listeners do not indicate healthy application
containers. No containers or volumes were changed. Full
PostgreSQL/Testcontainers verification and the live
DataMall-through-current-application path remain open; see the Phase 1 gates
above.

## 16 October 2026 Docker-backed reactor retry

The user confirmed the active Docker context as `desktop-linux`. From this
workspace, `docker info --format '{{.ServerVersion}}'` returned `29.1.3`, and
the existing `occ-phase1-traffic-20261016` and `occ-phase1-live-auth-20261015`
Compose projects were healthy. The first root `clean verify` run passed the 75
Ingestion unit/slice tests, then Testcontainers' Ryuk cleanup container exited
with code 1 before database integration tests could run. I stopped that Maven
run before treating Docker-backed checks as skipped.

I retried the root `clean verify` with
`TESTCONTAINERS_RYUK_DISABLED=true` for this process only. Ingestion's 75
unit/slice tests passed, Testcontainers connected to Docker 29.1.3, and
`DatabasePrivilegeBoundaryIT` began creating its PostGIS 18-3.6 container.
Container startup/inspection then stalled without a test result. I stopped the
Maven run rather than leave it waiting indefinitely. The test container had
been created as `2fd0e820777d` (`stoic_tesla`); Docker returned Engine API HTTP
500 for forced removal using both API 1.52 and 1.51, and a stop request did not
complete before it was interrupted. Its final state and cleanup are therefore
unconfirmed. No command targeted either existing Compose project, and no
volumes or `.env` contents were changed. The database-backed reactor suite
remains unverified in this retry; the prior successful 270-test run remains
historical evidence and predates the latest Ingestion changes.

## 16 October 2026 completed verification after container cleanup

Removed 27 identified unused containers: the old dispatch project, the duplicate
synthetic traffic project and their standalone helpers, the retired provider
stub, and two abandoned Testcontainers databases. This includes the previously
unconfirmed `2fd0e820777d` container. All removals succeeded. The live `.env`
project and all named volumes were retained. The duplicate running stack had
used roughly 1.2 GiB; removing it reduced concurrent resource use. This is not
proof that memory exhaustion caused the earlier Docker API failures.

The same root `mvnw.cmd --batch-mode --no-transfer-progress clean verify` then
passed in 3 minutes 50 seconds, with normal Ryuk cleanup enabled. Ryuk started
in 1.3 seconds and the first PostGIS test container in 3 seconds. Fresh XML
reports contain:

| Service | Unit/slice | Database integration | Total |
| --- | ---: | ---: | ---: |
| Ingestion | 75 | 12 | 87 |
| Disruption | 59 | 25 | 84 |
| Planning | 79 | 21 | 100 |
| Gateway | 19 | 0 | 19 |
| **Total** | **232** | **58** | **290** |

Failures, errors and skips are all zero. Both configured JaCoCo gates passed.
No Testcontainers containers remained afterward. The local build log is
`%TEMP%/occ-phase1-verify-20261016-clean.log`.

The four live application containers were stopped during image building. All
four application and three migration images rebuilt successfully. Compose
then recreated the existing `.env` project, including bootstrap and migration
jobs, without deleting its database volume. All jobs exited successfully and
all five app/database containers became healthy. Actual LTA traffic and real
Supabase/Gateway checks passed against these images; see [traffic workflow
evidence](phase1-traffic-planning-dispatch.md) and [live auth
evidence](phase1-supabase-live-auth.md). Runtime OpenAPI exported with a real
Supabase ADMIN session exactly matched the three committed service contracts.

Trivy 0.76.0, verified against the official release checksum, scanned these
exact rebuilt local image IDs with zero HIGH/CRITICAL findings. Scans used
offline mode and the cached vulnerability database updated
`2026-10-15T13:13:18Z`; they are bounded by that database snapshot. No findings
were waived with an allowlist or an ignore-unfixed override. CycloneDX SBOM
generation passed for all seven images.

| Image (`:local`) | Scanned image ID |
| --- | --- |
| `occ-ingestion` | `sha256:68ba188145826d3ab7ef38e0ce469c1d910c455cfb190c5e69127515772b8bbc` |
| `occ-disruption` | `sha256:f239d4c4eed42f26f09bb37d85a5e1b447f8d47eab270b1d626556b9e20c02f5` |
| `occ-planning` | `sha256:26bae6b6ffe3d76bcc3621ded253b2faf4c3a7822b31504c1b71c7428cf7b525` |
| `occ-gateway` | `sha256:cdf5c010979f0ccad7ddb7a14ee1847b7273cd11b18dbe2ab7d74d3b80503824` |
| `occ-ingestion-migrations` | `sha256:f6aa48458124834dae3c97249d2e4e3db04965c673bf403856082de07ecb2b3f` |
| `occ-disruption-migrations` | `sha256:c0cf1ed2d21f7ac99eb52a843208c479d10484911d126d572b90cc1715238a3c` |
| `occ-planning-migrations` | `sha256:03cbd4a820718cd9d96b062a3c4588d93e3dd700d593fc80b02d11d992c75e28` |

Reports and SBOMs are in `%TEMP%/occ-phase1-current-images-20261016/`.
Docker Desktop reports 22 CPUs and 4,103,536,640 bytes of container memory
(about 3.82 GiB). Only the five live app/database containers run at the end;
their exited-successful bootstrap/migration jobs remain part of that project.
The local development guide now requires one application stack at a time on
constrained engines and cleanup after system tests, including failure paths.
Hosted CI, the remaining process-crash boundaries and capacity/retention soak,
UNASSIGNED live authentication, final A15 filesystem cleanup, and authorized
deployment verification remain open.

The final working-source snapshot included 605 tracked/new files and explicitly
excluded the real `.env`. Trivy's HIGH/CRITICAL secret/misconfiguration gate
passed using embedded configuration checks (downloaded policy checks were not
available in the offline cache). The sanitizer reported zero HIGH/CRITICAL secret
or misconfiguration findings, removed its raw report, and retained only the safe
summary under `%TEMP%/occ-phase1-current-source-20261016/`. Node syntax checking,
the saved live traffic runner with `--restart`, and `git diff --check` passed.
The required local-development and deployment/recovery guides are now explicitly
allowed by `.gitignore`; they were previously hidden despite README links.
