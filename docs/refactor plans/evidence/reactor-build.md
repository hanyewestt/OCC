# Phase 1 reactor build evidence

Date: 15 October 2026, local Asia/Singapore. Source checkout: `fix/mvp-refactor`.

The root Maven wrapper ran from the repository root with Java 25.0.4:

```powershell
.\mvnw.cmd -ntp clean verify
```

Result: exit code 0. All four phase-one services compiled, packaged, and passed their configured test and coverage gates. Surefire/Failsafe reports contain 227 tests with zero failures, errors, or skips:

| Service | Unit/slice | PostgreSQL integration | Total |
| --- | ---: | ---: | ---: |
| Ingestion | 55 | 6 | 61 |
| Disruption | 51 | 21 | 72 |
| Planning | 60 | 18 | 78 |
| Gateway | 16 | 0 | 16 |
| **Total** | **182** | **45** | **227** |

Database integration tests ran against disposable Testcontainers PostgreSQL/PostGIS instances. They exercised existing Ingestion migrations, imported bunching state into Disruption, imported legacy planning rows, and verified the consolidated service persistence workflows. The gateway authorization suite includes missing-token, role-boundary, and valid-token-without-an-OCC-role cases.

The separate local Compose smoke test is recorded in `phase1-auth-smoke-test.md`; it covers the real Supabase JWKS integration, role results, service readiness, and local auth-test page.
