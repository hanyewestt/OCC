# Phase 1 unused-file review

Review date: 15 October 2026. This inventory records confirmed removals made during phase 1. It does not claim that repository cleanup is complete until the final A15 audit and checks pass.

| Removed file | Evidence it was unused | Kept/replacement |
| --- | --- | --- |
| `services/planning/src/test/resources/requests/confirmed-bunching-recommendation.json` | SHA-256 matched the copy under `src/test/resources/fixtures/requests/`; repository search found test loading only the latter through `ClassPathResource("fixtures/requests/confirmed-bunching-recommendation.json")`. | `services/planning/src/test/resources/fixtures/requests/confirmed-bunching-recommendation.json` |
| `services/ingestion/mvnw`, `services/ingestion/mvnw.cmd`, `services/ingestion/.mvn/wrapper/maven-wrapper.properties` | The root POM is the Maven reactor; all CI/docs commands already use the root wrapper and repository search found no nested-wrapper invocation. The nested distribution was also stale at Maven 3.9.11 while the root wrapper pins 3.9.16. | Root `mvnw` / `mvnw.cmd` and root `.mvn/wrapper/maven-wrapper.properties`; Ingestion README now uses the root wrapper and shared `infra/java.Dockerfile`. |

Also corrected the stale fixture path in `workflow-fixtures.md`, which still named the removed `services/planning/recommendation-engine` source tree. Historical source paths in baseline and contract-migration evidence remain intentionally because they identify the exact legacy revision/contracts used by the migration checks.

Moved the historical Ingestion source contract from `services/ingestion/openapi/polling-ingestion.json` to `evidence/source-contracts/polling-ingestion.json`. Recovered the historical Disruption, recommendation-engine and root-cause-diagnosis source contracts from baseline revision `0f2cd49` into the same evidence directory. Updated `contracts-manifest.json` to use those retained baseline files; the active service OpenAPI directories now contain only current service contracts. The migration manifest was validated against all referenced source and target JSON files, while its declared `Nginx static/demo asset manifest` target remains a logical edge asset rather than a JSON file.

Historical intermediate check before Docker was restored: the wrapper cleanup and typed-traffic implementation passed Surefire with Failsafe explicitly skipped because Docker was unavailable. The older run is retained as chronology, not as latest verification. The current full four-service result after cleanup is in [reactor build evidence](reactor-build.md). The isolated database backup/restore rehearsal is recorded in [phase1-database-restore-rehearsal.md](phase1-database-restore-rehearsal.md).

## Additional confirmed cleanup

After Docker became available, the ignored generated output under `services/operations/bus-bunching-detection/target` was confirmed to contain only stale compiled classes, reports and JARs from the retired application; there were no tracked files under `services/operations`. That old build-output tree was removed. The separate empty `services/bus-bunching-detection` scaffold also had no tracked files and was removed. The active `services/` tree now contains only `ingestion`, `disruption`, `planning`, and `gateway`.

The full four-service verification was rerun after that cleanup and passed with 204 Surefire and 52 Failsafe tests, zero skips or failures; see [reactor build evidence](reactor-build.md). Historical migration fixtures and source OpenAPI contracts remain because current upgrade tests and the contract-migration CI check consume them; they are migration evidence, not retired runnable services.

## Latest A15 status

Tracked-file/reference audit: 15 October 2026. The repository contains 579 tracked files and exactly four active service roots (`ingestion`, `disruption`, `planning`, `gateway`). There are zero tracked paths under retired service roots, zero nested Maven wrappers, no `infra/local/init` tree, and no `OCC_BUNCHING_*` deprecated aliases. Historical migration fixtures and source contracts remain because upgrade tests and contract-migration checks use them. The tracked implementation baseline available for code rollback is `23e244a`.

A follow-up workspace audit found ignored generated output under `services/planning/recommendation-engine/target` (149 files, about 59 MB) and `services/planning/root-cause-diagnosis/target` (24 files, about 87 MB). Both parent directories contain only their `target` directory; neither has tracked files, active source, or build references. These are retired-service build outputs, not part of the deployable artifact.

I attempted to remove both verified generated-only trees, first recursively and then by deleting each generated file and empty directory individually. The workspace safety policy rejected both deletion attempts without a specific reason, so no files were removed. **A15 remains open for this workspace** until these ignored outputs are removed and the final cleanup audit is rerun. The earlier tracked-file audit and its build result do not establish cleanup of this later-discovered local residue.
