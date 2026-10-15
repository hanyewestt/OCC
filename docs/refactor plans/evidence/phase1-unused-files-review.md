# Phase 1 unused-file review

Review date: 15 October 2026. This inventory records confirmed removals made during phase 1. It does not claim that repository cleanup is complete until the final A15 audit and checks pass.

| Removed file | Evidence it was unused | Kept/replacement |
| --- | --- | --- |
| `services/planning/src/test/resources/requests/confirmed-bunching-recommendation.json` | SHA-256 matched the copy under `src/test/resources/fixtures/requests/`; repository search found test loading only the latter through `ClassPathResource("fixtures/requests/confirmed-bunching-recommendation.json")`. | `services/planning/src/test/resources/fixtures/requests/confirmed-bunching-recommendation.json` |

Also corrected the stale fixture path in `workflow-fixtures.md`, which still named the removed `services/planning/recommendation-engine` source tree. Historical source paths in baseline and contract-migration evidence remain intentionally because they identify the exact legacy revision/contracts used by the migration checks.

Post-removal verification: `mvnw.cmd --batch-mode --no-transfer-progress -pl services/planning -am verify` passed after the duplicate was removed. Planning ran 60 Surefire tests and 18 PostgreSQL-backed Failsafe tests, with zero failures, errors, or skips. The isolated database backup/restore rehearsal is recorded in [phase1-database-restore-rehearsal.md](phase1-database-restore-rehearsal.md).
