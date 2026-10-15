# Phase 1 reactor build evidence

Date: 15 October 2026, local Asia/Singapore. Source checkout: `fix/mvp-refactor`.

Latest run: the root Maven wrapper ran from the repository root with Java 25.0.4 on 15 October 2026:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

Result: exit code 0; Maven reported `BUILD SUCCESS` and 11:40 total time. All four phase-one services compiled and packaged, and passed their configured test and coverage gates. The current Surefire/Failsafe reports contain 232 tests with zero failures, errors, or skips:

| Service | Unit/slice | PostgreSQL integration | Total |
| --- | ---: | ---: | ---: |
| Ingestion | 56 | 9 | 65 |
| Disruption | 51 | 21 | 72 |
| Planning | 60 | 18 | 78 |
| Gateway | 17 | 0 | 17 |
| **Total** | **184** | **48** | **232** |

Database integration tests ran against disposable Testcontainers PostgreSQL/PostGIS instances. They exercised existing Ingestion migrations, imported bunching state into Disruption, imported legacy planning rows, and verified the consolidated service persistence workflows. The additional Ingestion tests verify that scheduled provider polling is disabled in the isolated test stack and that `/demo.html` remains public while the demo control API remains authenticated. The gateway authorization suite includes missing-token, role-boundary, and valid-token-without-an-OCC-role cases.

The separate local Compose smoke test is recorded in `phase1-auth-smoke-test.md`; it covers the real Supabase JWKS integration, role results, service readiness, and local auth-test page.
