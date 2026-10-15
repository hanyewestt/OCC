# Phase 1 reactor build evidence

Date: 15 October 2026, local Asia/Singapore. Source checkout: `fix/mvp-refactor`.

The latest four-service verification ran from the repository root with Java 25.0.4:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -pl services/ingestion,services/gateway,services/disruption,services/planning verify
```

Result: exit code 0; Maven reported `BUILD SUCCESS` in 6:29. All four phase-one services compiled and packaged. The configured Ingestion and Disruption JaCoCo gates passed; Planning and Gateway do not currently have configured coverage thresholds. Fresh Surefire and Failsafe reports for the four target services contain 269 tests with zero failures, errors, or skips:

| Service | Unit/slice | PostgreSQL integration | Total |
| --- | ---: | ---: | ---: |
| Ingestion | 58 | 10 | 68 |
| Disruption | 59 | 25 | 84 |
| Planning | 78 | 21 | 99 |
| Gateway | 18 | 0 | 18 |
| **Total** | **213** | **56** | **269** |

After that reactor run, the Ingestion feed-sequence gauge query was aligned with the consumer's durable feed criteria, including both stop-scope formats and successful poll-run status. The focused follow-up command `.\mvnw.cmd --batch-mode --no-transfer-progress -pl services/ingestion '-Dit.test=IngestionStartupIT' verify` passed all 56 Ingestion unit/slice tests and all 6 selected `IngestionStartupIT` tests; its Ingestion JaCoCo gate passed.

Database integration tests ran against disposable Testcontainers PostgreSQL/PostGIS instances. They exercised Ingestion migrations and privilege boundaries, legacy bunching import into Disruption, traffic incident lifecycle/outbox persistence, legacy Planning imports, Planning request/recommendation persistence, and receiver/idempotency workflows. `BunchingDiagnosisDispatcherIT` includes a lost-response case: the receiver harness records the first request before the response is dropped, and the retry uses the same idempotency key and payload; the outbox is delivered on attempt two. This is not a multi-container Planning receiver commit/restart fault-injection test. Ingestion tests also verify the isolated test stack disables scheduled live-provider polling and keeps `/demo.html` public while protecting demo control APIs.

All four services now expose `/actuator/prometheus` and authorize it for `ADMIN` only. Integration tests verify unauthenticated access is rejected and an ADMIN token can read the metrics; Gateway tests additionally verify a CONTROLLER token is forbidden. The metrics include Spring Boot/Micrometer JVM, HTTP, and database-pool measurements where applicable, plus low-cardinality service gauges:

- Ingestion: oldest successful feed age, unhealthy feed count, and latest durable arrival feed sequence.
- Disruption: consumed feed sequence, deferred source count, pending Planning outbox count/age, expired leases, retained delivery-attempt total, and traffic-poll age.
- Planning: pending request/job counts and ages, retained deterministic-fallback count, and retained provider-failure count.
- All three business services: process-lifetime counters for rows removed by their scheduled retention cleanup.

Custom gauges and retention counters use no per-event, user, or provider-payload labels. Nginx denies management paths, Gateway does not proxy backend actuator paths, and production Compose does not publish the backend service ports. These metrics provide operational visibility but do not close A13: representative-host capacity/load and retention-soak results, hardware details, and k6 scenarios remain outstanding.

This Maven reactor run uses test fixtures and does not establish live-provider behavior or hosted CI status. The local `.env` contains non-empty `DATAMALL_API_KEY` and `OCC_PLANNING_GEMINI_API_KEY`; no credential values are recorded here. The read-only DataMall request using `DATAMALL_API_KEY` returned HTTP 200 with a valid 500-row page. A separate one-request Gemini smoke using `OCC_PLANNING_GEMINI_API_KEY` and synthetic evidence returned HTTP 200 in 4.8 seconds; the JSON response validated with `causeCode=UNKNOWN`. That smoke verified key/model connectivity and structured output only, not the Planning service workflow. `OCC_PLANNING_RECOMMENDATION_AI_ENABLED=false`, so the deterministic end-to-end recommendation verification used its configured fixture. Live auth and provider results are recorded in [functional workflow evidence](phase1-functional-workflow.md) and [Supabase evidence](phase1-supabase-live-auth.md). The separate synthetic Nginx edge checks remain in [edge evidence](phase1-nginx-edge-smoke-test.md).

Compose configuration validation passed with the safe root `.env.example` for both the base stack and the production merge. Direct validation with the user's `.env` currently stops because `OCC_INGESTION_DB_MIGRATOR_PASSWORD` and `OCC_DISRUPTION_DB_MIGRATOR_PASSWORD` are unset; those local values are required before the unoverridden base stack can be started from that file.
