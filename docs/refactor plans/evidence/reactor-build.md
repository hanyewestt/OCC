# Phase 1 reactor build evidence

Date: 15 October 2026, local Asia/Singapore. Source checkout: `fix/mvp-refactor`.

Latest verification ran after the confirmed legacy build-artifact cleanup, from the repository root with Java 25.0.4:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -pl services/ingestion,services/gateway,services/disruption,services/planning verify
```

Result: exit code 0; Maven reported `BUILD SUCCESS` in 4:50. All four phase-one services compiled and packaged, all required tests executed, and configured Ingestion, Disruption, and Planning coverage gates passed. Surefire and Failsafe reports contain 256 tests with zero failures, errors, or skips:

| Service | Unit/slice | PostgreSQL integration | Total |
| --- | ---: | ---: | ---: |
| Ingestion | 56 | 9 | 65 |
| Disruption | 56 | 23 | 79 |
| Planning | 75 | 20 | 95 |
| Gateway | 17 | 0 | 17 |
| **Total** | **204** | **52** | **256** |

Database integration tests ran against disposable Testcontainers PostgreSQL/PostGIS instances. They exercised Ingestion migrations and privilege boundaries, legacy bunching import into Disruption, traffic incident lifecycle/outbox persistence, legacy Planning imports, Planning request/recommendation persistence, and receiver/idempotency workflows. Ingestion tests also verify the isolated test stack disables scheduled live-provider polling and keeps `/demo.html` public while protecting demo control APIs. Gateway tests cover missing tokens, role boundaries, and a valid JWT without an OCC role.

This reactor run uses test fixtures and does not establish live provider behavior or hosted CI status. Live Supabase, DataMall, and Gemini checks are recorded in [functional workflow evidence](phase1-functional-workflow.md) and [Supabase evidence](phase1-supabase-live-auth.md). The separate synthetic Nginx edge checks remain in [edge evidence](phase1-nginx-edge-smoke-test.md).

Compose configuration validation passed with the safe root `.env.example` for both the base stack and the production merge. Direct validation with the user's `.env` currently stops because `OCC_INGESTION_DB_MIGRATOR_PASSWORD` and `OCC_DISRUPTION_DB_MIGRATOR_PASSWORD` are unset; those local values are required before the unoverridden base stack can be started from that file.
