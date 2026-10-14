# Phase 1 implementation baseline

Captured 15 October 2026 from commit `0f2cd49` (`chore: checkpoint retention fixes and phase one refactor plans`). The working tree was clean before verification. A read-only Docker Compose service-list/status check parsed the ignored root `.env` but printed no environment values; it did not start containers or change data. Do not copy those local values into evidence.

## Toolchain

| Tool | Observed version |
| --- | --- |
| Java | 25.0.4 LTS |
| Docker Engine | 29.1.3 (Docker Desktop) |
| Docker Compose | 5.0.0-desktop.1 |
| Existing per-service Maven wrapper | 3.9.11 |
| Spring Boot | 4.1.1 |
| PostgreSQL used by integration tests | 18.6 |

## Baseline verification

Each current runnable service was verified separately with its own wrapper and Docker-backed PostgreSQL integration tests. These results are pre-refactor evidence, not proof of the new architecture.

| Current service | Unit/slice tests | Integration tests | Total | Failures / errors / skips | Result |
| --- | ---: | ---: | ---: | --- | --- |
| `services/ingestion` | 55 | 6 | 61 | 0 / 0 / 0 | PASS |
| `services/operations/bus-bunching-detection` | 51 | 18 | 69 | 0 / 0 / 0 | PASS |
| `services/planning/recommendation-engine` | 53 | 15 | 68 | 0 / 0 / 0 | PASS |
| `services/planning/root-cause-diagnosis` | 17 | 9 | 26 | 0 / 0 / 0 | PASS |
| **Total** | **176** | **48** | **224** | **0 / 0 / 0** | **PASS** |

Commands were `.\mvnw.cmd --batch-mode --no-transfer-progress verify` from each service directory. Test counts were read from the generated Surefire/Failsafe XML reports. Coverage checks passed where configured. No live DataMall, Gemini, Supabase or EC2 test was run.

## Structural facts at baseline

- Four current applications exist: Ingestion, Bunching, Recommendation, Diagnosis. Gateway and the canonical Disruption/Planning applications do not exist.
- Bunching detection exists. Traffic incident observations and demo/context inputs exist under Ingestion, but no independent traffic incident detector/lifecycle was found; phase 1 must implement that lifecycle within Disruption.
- Ingestion has 17 migrations; bunching has 16; recommendation has 7; diagnosis has 6. The latest ingestion, bunching and planning upgrade tests validate migration behavior using PostgreSQL containers.
- The separate Diagnosis -> Recommendation HTTP delivery and Bunching -> Diagnosis outbox are current workflows. Their durable/idempotent behavior must be characterized before replacing service boundaries.
- The local stack is currently `infra/local/compose.yaml`, has three schema-init jobs and old service names. It publishes database and core-service ports for development.
- No root Maven reactor or root wrapper exists. Existing runnable services have duplicated wrappers and Dockerfiles.
- A standalone traffic incident detection service and product login page were not found.

## Known implementation constraints

- Keep this revision as the code rollback point. Do not delete the user's persistent Compose volume or migrate it implicitly.
- No production database was inspected or changed. Migration rehearsals must use disposable PostgreSQL/PostGIS containers and preserve all table/evidence mappings.
- Phase 1 must not add approval, rejection, captain identity binding, assigned instructions, or product React pages.
- The configured development Supabase project and test accounts are used only for an explicit live-auth check; ordinary CI stays offline and fixture-based.
