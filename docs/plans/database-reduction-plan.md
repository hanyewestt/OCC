# Database reduction plan

Status: Phases 0-7 implemented and locally verified on 2026-10-13. See [verification and breaking changes](database-reduction-verification.md) for applied versions, test results, retention exceptions, and rollback requirements. Phases 8-10 remain optional and unimplemented.

Basis: repository init scripts, service migrations, current readers/writers, and the local running database inspection. The original starting point was 29 application tables: ingestion 13, bunching 10, diagnosis 4, recommendation 2. The verified reduced catalog contains 24: ingestion 13, bunching 5, diagnosis 4, recommendation 2. PostgreSQL extension objects and Flyway history are excluded.

## Scope and order

Preserve the four services, live/demo isolation, deterministic detection rules, idempotency, and durable delivery. Remove general-purpose database audit history and audit-writing functionality. Preserve and improve AI-generated response audit records for both diagnosis and recommendation, including the input snapshots required to understand them after source data expires. Retain operational history and topology for a configurable window, with 12 hours as the proposed default. Implement Phases 0-7 first; Phases 8-10 are optional.

The user's revised scope supersedes the earlier permanent general-audit requirement. The audit/retention rules in `docs/CONVENTIONS.md` and affected service documentation follow that policy. The user authorized immediate destructive contraction for the disposable local database; old service binaries must not be run against the contracted schema. This is not authorization to delete another deployment's data.

| Phase | Change | Relative effort | Main owners |
|---|---|---|---|
| 0 | Establish data and behavior baseline | Small | All services |
| 1 | Retire unused provisioning, remove failure audit, preserve AI audit | Small-medium | Infrastructure, ingestion, bunching, planning |
| 2 | Remove exact timestamp duplicates | Small | Recommendation, ingestion |
| 3 | Compact recommendation persistence and unify its identity | Medium | Recommendation |
| 4 | Remove reverse diagnosis relationship | Medium | Diagnosis |
| 5 | Replace processing audit with operational state; merge selection/cursor | Medium-high | Bunching |
| 6 | Unify live/demo alert state and simplify persisted scope | High | Bunching |
| 7 | Bound operational and topology history to configured retention | High, required | Ingestion, bunching; evidence consumers |
| 8 | Normalize ingestion metadata shared with parent rows | High, conditional | Ingestion; all API consumers |
| 9 | Replace geometry storage and retire spatial runtime dependency | High, optional | Ingestion, infrastructure |
| 10 | Combine diagnosis and recommendation services | Highest, optional | Planning services, infrastructure, clients |

Phases 2-4 can be implemented independently after Phase 0. Phase 6 should follow Phase 5 to avoid changing the same feed-selection code twice. Phase 7 is required: current-only topology is no longer proposed. Phase 10 requires a separate decision about the assignment's service architecture and must preserve both classes of AI response audit.

### Storage policy

| Existing storage | Revised treatment |
|---|---|
| `source_processing_audit` | Remove the table, entity/repository, and all audit writes. |
| `bunching_observation` | Remove the full append-only evaluation ledger; preserve only deduplication/result state in the unified source-processing table. |
| `alert_event` | Keep events required by active alerts, demo display, diagnosis dispatch, and recent history; remove audit actor metadata and permanent event-history retention. `action` remains a functional OPENED/CLEARED transition. |
| `poll_run`, `raw_observation`, normalized observations | Keep as functional ingest/history data within the configured window; remove audit-only fields and permanent retention exceptions after checking readers. |
| `reference_dataset_version` and topology children | Keep current topology plus versions effective during the retained history window; prune unused expired versions. |
| `consumer_selection`, `ingestion_feed_cursor` | Replace with one operational `consumer_state` row. |
| diagnosis/recommendation result records and both `model_invocation_record` tables | Preserve AI audit, including generated responses, validation/fallback outcomes, versions, timings, usage, and self-contained input/context snapshots. |
| workflow requests, leases, outboxes, delivery state | Keep only for work/recovery/idempotency; do not treat them as permanent general audit logs. Prune completed operational rows when no retained AI record needs them. |
| Flyway history | Keep; it is migration bookkeeping, not application audit functionality. |

Ordinary sanitized application diagnostics may remain. Do not replace removed database audits with an append-only file/event audit subsystem. Classification, scope identity, transition actions, hashes, and timestamps that are necessary for correctness or retained AI evidence remain supported.

## Rules applying to every phase

- Append new Flyway migrations; never change an applied migration or erase Flyway history. Current maximum versions are ingestion V014, bunching V11, diagnosis V001, recommendation V002; allocate subsequent versions against the actual branch at implementation time.
- Use an expand/transition/contract sequence where necessary. Release A supports the old schema plus new objects and keeps old persisted data populated. Release B changes the authoritative reader/writer. Physical removal happens only after the previous release's rollback window closes. One phase can span several releases and migrations.
- For table merges, old and new workers must not process the same data using different lock targets. Default to a controlled worker stop for backfill and cutover: finish in-flight transactions, block new claims, switch all workers, then resume. Retain legacy writes during the compatibility release. If overlapping versions must run, implement and test database mirroring and a shared locking protocol before allowing overlap; an uncoordinated application dual-write is insufficient.
- Before contraction, capture schema/data backups and run parity queries. Rollback before contraction means reverting the application while retained data remains synchronized. After contraction, rollback requires restoring the backup or an explicit forward migration that recreates and backfills old objects; reverting the binary alone is unsafe.
- Existing environment-variable names remain supported unless their retirement is explicitly approved. New deployment settings use typed configuration properties and are documented in root `.env.example`, Compose, YAML bindings, and the owning README. Do not introduce operational constants for rollout settings.
- Keep public/internal response field names and meanings stable. Database columns may disappear while DTO fields are reconstructed. Regenerate the relevant OpenAPI contract whenever implementation changes the API; verify contract parity for persistence-only changes.
- Run `mvnw verify` in affected services with PostgreSQL integration tests actually executing. Update schema fixtures and test-resource copies alongside production migrations. Add fresh-install and upgrade tests: a fresh database passing migrations does not prove existing data can migrate.
- Keep each phase in a focused change with its own completion criteria. Do not mix unrelated dirty-worktree changes into implementation.

## Phase 0: establish the baseline

### Work

1. Inspect the real database catalog, Flyway versions, role ownership, row counts, indexes, and views. Confirm whether `operations` and `planning` are genuinely empty and whether any external readers use the two bunching views.
2. Record representative API responses for live arrivals, arrival history, live/demo snapshots, active alerts, confirmed event facts, diagnosis requests/results, and recommendation lookup/replay.
3. Check the proposed equality assumptions against historical data: recommendation ID equals idempotency key; model invocation creation equals completion; topology child import times equal their version time; raw source/status equal their poll-run values; normalized metadata equals its raw parent; scope matches demo identity.
4. Record current query plans for feed paging, current arrivals, history paging, retry polling, and active-alert reads. Use representative local data; avoid claiming a tiny fixture proves production performance.
5. Locate all test SQL and init-script references. Bunching's `src/test/resources/db/ingestion-schema.sql` and `bunching-schema.sql` can drift from Flyway and must be checked.
6. Classify every use of audit/evidence metadata as operational state, retained AI evidence, or removable general audit. Identify foreign keys from retained AI records to expiring request/delivery/source data before defining cleanup.
7. Verify AI response coverage: diagnosis invocation rows currently contain metadata but no per-attempt generated response. Recommendation rows retain structured candidates; verify rejected/malformed responses and failed processing paths are also captured without inventing an output when the provider returned none.

### Exit criteria

The existing live/demo flow passes, the baseline is documented, and every candidate has a proven equality or an explicit exception policy. A mismatch blocks removal of that candidate until its meaning is understood. Preserve differing historical values; do not silently overwrite them to make a parity check pass.

## Phase 1: remove unused provisioning and integration

### Changes

- Stop creating the reserved `operations` and `planning` schemas and the `occ_operations`/`occ_planning` roles in `infra/local/postgres/init/01_schemas_and_roles.sh` for new installations.
- Retire required mappings of `OCC_OPERATIONS_DB_PASSWORD` and `OCC_PLANNING_DB_PASSWORD` in `infra/local/compose.yaml`. Document these two names as deprecated, accepted but unused during transition; remove their inventory entries only after an explicit configuration migration is accepted.
- Remove corresponding synthetic password setup from `IngestionStartupIT` and `IngestionWiringIT`; update `infra/local/README.md` and ingestion documentation.
- After confirming no external reader needs them, retire `ingestion.bunching_snapshot` and `ingestion.bunching_route_direction`. Current bunching source access is HTTP through `HttpStoredArrivalAdapter` and `HttpLiveFeedHeadAdapter`, not these database views.
- Remove the `bunching-read-grants` Compose job, its dependency from `bus-bunching-detection`, `wait_and_grant_bunching_views.sh`, and `03_bunching_read_grants.sh`. Replace the dependency with the bunching schema-init job and ingestion readiness as appropriate. Keep `ingestion.current_bus_arrival`, which has an active repository reader.
- Update ingestion startup tests that currently assert the retired views exist. Verify no test-resource migration ordering depends on the grants job.

### Existing deployments and validation

Init scripts run automatically only on new database volumes. Provide a separate administrator cleanup procedure for existing volumes: inspect emptiness/ownership, revoke obsolete grants, and remove only verified-unused schemas/roles. Never use `DROP ... CASCADE` to bypass unexpected dependencies. Do not execute this cleanup as part of a service's Flyway migration: application users should not own global role administration.

Test both a new Compose database and startup against an existing volume. The bunching HTTP feed must continue working without cross-schema ingestion grants. Verify `current_bus_arrival` remains available.

### Benefit and rollback

Two reserved schemas/roles, two required unused secrets, two unused views, and a startup helper can be retired. Retain/recreate provisioning and views during the rollback window; keep their old grants if a previous deployed version still requires them. This provisioning portion changes no application-table count; audit removal below removes one table.

### Remove general failure audit

- Stop `SourceProcessingAudit` inserts in `HttpStoredArrivalAdapter.reject` and `defer`; remove its constructor dependencies, entity, repository, and tests that only prove audit writes.
- Drop `bunching.source_processing_audit` and its indexes in a new contract migration after the compatibility release. Stop extending the audit schema in new migrations; leave applied migrations intact.
- Retain latest retry/rejection status and reason, atomic state persistence, and visible processing failures. Failure to persist a retry must still fail the cycle; removing audit must not acknowledge work without recovery state.
- Update `BunchingRecordingIT`, adapter tests, copied schema fixtures, README audit claims, and `docs/CONVENTIONS.md`. Snapshot/export existing audit rows before contraction if rollback requires them; an operational backup is not a replacement permanent audit feature.
- This part reduces the application-table count from 29 to 28 after contraction.

### Preserve and complete the AI audit exception

- Keep `recommendation.recommendation_record`, `recommendation.model_invocation_record`, `diagnosis.root_cause_diagnosis`, and `diagnosis.model_invocation_record`. Do not disable AI audit writes or apply the operational 12-hour cleanup to them.
- Add a diagnosis per-attempt response field through a new migration. Capture the actual structured/generated response for initial and repair attempts, validated or rejected, along with validation outcome and final disposition. Current `ModelInvocationDetails`/`ModelInvocationRecord` only store metadata; the final diagnosis is not a substitute for every model output.
- Update `GeminiRootCauseModelAdapter`, `ModelCallResult`/`ModelInvocationDetails`, `RootCauseDiagnosisProcessor`, completion/failure persistence, and their tests. Preserve successful and failed-attempt records even if the diagnosis cannot complete. Make sure persistence does not depend on a successful final result or on a non-null evidence hash alone.
- For recommendation, keep `candidate_payload` and final `response_payload`, and capture a bounded sanitized text response when decoding fails, if a response actually exists. Check `GeminiMitigationCandidateAdapter`, the orchestrator, and JDBC audit writer for paths that currently discard invalid output. Provider failures with no response still record metadata/outcome without fabricating payload.
- Recommendation invocation rows currently require an existing `recommendation_record` foreign key. For attempts with no final recommendation, allow a separately correlated invocation record with its own input snapshot and nullable final-result reference, using the existing invocation table. Preserve the invocation UUID, request/idempotency correlation, and response even when final result persistence/validation fails. Test this migration against success-path foreign keys and concurrent idempotent retries.
- Retain provider/model/response identifiers, prompt/schema versions, input snapshots or references to retained immutable AI input, token counts, timestamps/latency, validation errors, fallback use, and generated outputs. Do not record credentials, HTTP authentication headers, or unrelated provider transport bodies.
- If introducing audit payload-size limits, expose them through typed settings and root `.env.example`, YAML/Compose bindings, and both planning READMEs. Make truncation explicit. No new short retention policy is assigned to AI audit by this plan; any later AI retention setting must be a separate documented policy.
- Regenerate OpenAPI if any audit inspection endpoint or existing result behavior changes. Validate successful, rejected, malformed, repaired, and no-response invocations and verify records survive operational cleanup.

## Phase 2: remove exact timestamp duplicates

### Changes

- Remove `recommendation.model_invocation_record.created_at` after Phase 0 confirms historical equality with `completed_at`. `JdbcModelInvocationRecordAdapter` currently supplies `completedAt()` to both columns. Switch any reporting/ordering to `completed_at`, and drop or replace `ix_model_invocation_created_at` rather than retaining a redundant index.
- Remove `ingestion.bus_stop.imported_at`, `bus_service.imported_at`, and `bus_route_stop.imported_at` once their equality to `reference_dataset_version.imported_at` is proven. Read the import time from the parent if it is needed.

### Dependencies

Update `JdbcModelInvocationRecordAdapter`, recommendation integration SQL, the `BusStop`/`BusService`/`BusRouteStop` entities and constructors, and `ReferenceTopologyService.replaceIfChanged`. Update directly inserted test fixtures, copied ingestion migrations, service READMEs, and database documentation. Preserve the timestamp on `reference_dataset_version`.

### Transition and validation

Release A keeps legacy values populated while consumers stop using them. Release B drops child mappings and relaxes legacy NOT NULL requirements as necessary; later contraction drops the columns. Verify import, no-change import, activation, version-scoped reads, and model success/failure auditing. Preserve old column values in backup if any historical differences exist.

### Benefit

Four exact-duplicate columns disappear with a limited dependency surface. Do not remove `latency_millis`/`latency_milliseconds` or `total_tokens` under the same assumption: provider totals need not equal prompt plus completion, and measured latency need not equal wall-clock timestamp subtraction.

## Phase 3: compact recommendation persistence

### Target

Keep these nine columns initially:

`recommendation_id`, `request_hash`, `bunching_event_id`, `direction_id`, `adjustment_seconds`, `request_payload`, `mitigation_context`, `response_payload`, `created_at`.

Use `recommendation_id` as both the stored primary key and the API idempotency identity. Preserve the external `Idempotency-Key` header and returned recommendation UUID. Retaining `recommendation_id` keeps `model_invocation_record.recommendation_id` and its foreign key intact.

### Changes

- Remove duplicate `idempotency_key`, `request_id`, `diagnosis_id`, `classification`, `service_id`, `monitored_stop_code`, `mitigation_type`, `recommendation_status`, `rationale`, `target_bus_reference`, `control_stop_code`, and `evidence_ids` columns.
- Reconstruct those values from the immutable request/response JSON. The current adapter already deserializes those payloads when reading recommendations.
- Update `JdbcRecommendationRecordAdapter` lookup by idempotency key to query `recommendation_id`; update its insert, conflict target, and row mapping. The domain `RecommendationRecord` may continue exposing an idempotency key even though persistence stores one identity.
- Keep indexed `bunching_event_id` for the active API lookup. Replace its single-column index with a query-appropriate `(bunching_event_id, created_at DESC)` index if the baseline supports it. Remove the old diagnosis index and identity-equality constraint when their columns disappear.
- Keep the existing direction and hold-limit database checks through `direction_id` and `adjustment_seconds`. Add checks that these projections and `bunching_event_id` match their JSON values. Removing scalar projections without replacement checks would weaken database validation.
- Removing the last two scalar projections is a separate follow-up only if equivalent JSON-path checks are tested for missing keys, nulls, types, bounds, and SQL CHECK's nullable behavior. Keep the original intentional invariants unless separately changed.

### Dependencies

`JdbcRecommendationRecordAdapter`, `JdbcModelInvocationRecordAdapter` where identity assumptions are tested, `DurableBunchingRecommendationService` tests, `BunchingRecommendationFlowIT`, controller tests, fixtures, and the recommendation README. Its integration test currently selects and asserts scalar columns directly; rewrite those assertions against JSON and retained constraints.

Downstream `HttpRecommendationLookupAdapter` and `HttpBunchingRecommendationAdapter` should require no code changes if the HTTP contract stays identical. Exercise both consumers to prove this. No schema-to-schema join should be introduced.

### Transition, validation, rollback

First audit UUID equality and JSON/scalar parity. Continue populating legacy columns in Release A. In Release B, switch identity reads and remove old mappings while allowing old columns to be null or maintaining a temporary compatibility trigger. Drop old columns only after rollback support expires.

Test same-key/same-request replay, same-key/different-request 409, concurrent insert behavior, event lookup ordering, model-record foreign keys, and deserialization of historical JSON. A rollback backfills legacy fields from verified payloads and restores the former insert path.

### Benefit

The record goes from 21 to 9 columns. It remains one table with the evidence and replay behavior intact.

## Phase 4: remove the reverse diagnosis link

### Changes

- Use `root_cause_diagnosis.request_id` as the sole persisted relationship. It already has a unique constraint and a repository method `findByRequestId`.
- Remove `diagnosis_request.diagnosis_id` after consumers resolve the diagnosis through that relationship.
- Change `DiagnosisRequest.complete` to update lifecycle state without storing the diagnosis UUID. Keep diagnosis creation, request completion, and recommendation enqueue in the same transaction.
- Change `DiagnosisQueryService.findRequest` to return the associated diagnosis ID from a left join/projection or repository lookup.
- Cover the POST path too: `DiagnosisRequestController.create` currently reads `request.diagnosisId()` directly. A duplicate enqueue of a completed request must still return its diagnosis ID. Return a service-level request view or resolve that view after enqueue; do not inject a repository into the controller.

### Dependencies and proof

Update `DiagnosisRequest`, `DiagnosisCompletionService`, `DiagnosisQueryService`, `DiagnosisRequestService` return/view handling, `DiagnosisRequestController`, lifecycle tests, and `DiagnosisRequestPersistenceIT`.

Keep `diagnosisId` in the POST/GET DTOs and OpenAPI contract. Verify pending/failed requests return null, completed requests return the original ID, duplicate enqueue remains idempotent, and rollback does not strand completed records. Retain the old field populated during Release A; remove its mapping before contraction in a later release.

### Benefit

One redundant relationship column disappears. Keep request and result as separate tables: failed/pending requests have no result and can have multiple model attempts.

## Phase 5: replace processing audit and merge consumer state

### Target

Introduce `bunching.source_processing_state` with:

`raw_observation_id` (primary key), `status` (`PROCESSED`, `DEFERRED`, or `REJECTED`), `next_attempt_at`, `processed_at`, `rejected_at`, `reason`, `demo_run_id`, and only the result fields required by the existing processing return contract.

Temporarily retain `feed_scope`/`classification` if needed for compatibility; simplify them in Phase 6. Add status checks requiring a deadline for deferred rows and rejection time/reason for rejected rows, plus an index supporting scoped due-retry reads. The PROCESSED marker replaces `bunching_observation` as the deduplication guard. `RecordedBunching` currently also returns original evaluation time, threshold, and bunched-group count on replay; retain those minimal values initially or explicitly simplify the internal return contract and update its callers/tests. Do not retain per-observation evidence JSON, audit actor/action, or a permanent evaluation timeline.

### Changes and dependencies

- Replace `SourceRetry`, `SourceRejection`, and `BunchingObservation` entities/repositories with one state entity/repository. Remove `bunching_observation` after migrating its necessary success/result markers.
- Update `HttpStoredArrivalAdapter.pendingIds`, `snapshot`, `retryFeed`, `reject`, `defer`, `complete`, and its constructors/tests. Keep scoped retry ordering, terminal-rejection exclusion, and durable feed acknowledgment.
- In backfill, completed evaluations supersede stale retry rows; rejection supersedes retry when both old rows exist. Preserve the latest rejection state; diagnose inconsistent identity before resolving it. Do not import failure-attempt history into a new audit table.
- Remove full evaluation-ledger writes in `BunchingRecordingService` and switch its existence guard/result lookup to source-processing state. A successful processing marker, alert state/event, and diagnosis outbox enqueue must commit atomically. Concurrent handling of the same source must have a single winner; failure rolls back the marker so a retry can run.
- Narrow `BunchingEvidencePort`/`JacksonBunchingEvidenceAdapter` to operational alert facts and retained AI input needs. Remove encoding paths used only to write the deleted evaluation ledger; preserve alert/context DTO fields required by downstream clients.
- Preserve behavior when the cursor advances past a deferred source: that source must remain recoverable from the new retry state after restart.

### Cutover and validation

Add the new table, migrate old rows with idempotent upserts, and verify counts by status and run. Use the common worker cutover protocol; keep all three legacy representations usable during the rollback window. Remove old repositories/tables only at contraction.

Exercise deferred-to-completed, deferred-to-rejected, repeated defer, repeated rejection, expired/missing source, switching runs, restart recovery, failures to persist a deadline, and concurrent attempts. Update `HttpStoredArrivalAdapterTest`, `BunchingRecordingIT`, `bunching-schema.sql`, and the bunching README.

### Benefit

Three tables become one: after the Phase 1 audit-table removal, 28 application tables become 26.

### Merge feed selection and checkpoint

- Replace `consumer_selection` and `ingestion_feed_cursor` with singleton `bunching.consumer_state`: `id`, `mode`, `demo_run_id`, `last_sequence`, `updated_at`, `version`. Enforce LIVE/null-run and DEMO/non-null-run identity, nonnegative sequence, and singleton ID.
- Backfill by checking selection mode/run against cursor identity. Do not combine a cursor from one run with selection for another. Resolve mismatches by the documented existing selection behavior, not by taking the largest sequence.
- Update `ConsumerSelectionService`, `HttpStoredArrivalAdapter`, `HttpLiveFeedHeadAdapter` dependencies, selection controller/service views, and all repositories/entities/tests referencing the old tables.
- Preserve reset-to-zero when selecting a different demo run, the existing behavior when reselecting a run, jump-to-current-live-head on return to LIVE, and rejection/defer checkpoint acknowledgment.
- Acknowledge only against the feed identity selected for that work. Use optimistic version checks or row locking so delayed work from an old selection cannot advance the new checkpoint. Prevent stale entity writes from overwriting a concurrent selection change.
- Keep ingestion's `demo_runtime_setting` separate: enabling demo functionality and selecting the detector's input remain different service responsibilities.
- Test fresh startup, restart, repeated selection, switches between two runs, switch to live, concurrent acknowledgment/selection, HTTP response compatibility, and rollback parity. Then drop the two old tables and retain the replacement.
- This merge reduces the count from 26 to 25 application tables.

## Phase 6: merge alert state and simplify scope

### Part A: shared alert-state storage

- Add `bunching.alert_processing_state` with surrogate UUID `id`, nullable `demo_run_id`, `service_id`, `stop_code`, `direction_id`, `last_fetched_at`, `active_alert_id`, and the existing demo observation/confirmation fields.
- Enforce `UNIQUE NULLS NOT DISTINCT (demo_run_id, service_id, stop_code, direction_id)` so there is exactly one live row per group and one row per demo run/group. A surrogate key avoids nullable JPA embedded identifiers; do not invent a fake demo-run UUID for live data.
- Backfill `alert_state` as live rows and `demo_alert_state` as scoped rows. Preserve active episode IDs, last timestamps, observation IDs, and pending candidates exactly.
- Replace state entities/repositories and their lock/insert methods. Update `JpaBunchingAlertAdapter` to use the shared storage while retaining separate live and demo transition policies.
- Update `AlertEventRepository.findCurrentLiveOpenAlerts` and its count query to join the new state and explicitly filter live scope. `LiveBunchingAlertService`, ingestion's live-alert proxy, and demo APIs must retain their results.
- Preserve the transaction encompassing evaluation, locked state update, alert event, notification, and diagnosis outbox enqueue. Demo confirmation still requires two qualifying observations; live alert behavior must not change as a side effect of shared storage.

### Part B: remove redundant stored scope strings

- Derive `feed_scope` from `demo_run_id` in `source_processing_state`, `alert_event`, and `consumer_state`. The former evaluation and failure-audit tables are removed in earlier phases.
- Update scoped repository queries to use `demo_run_id IS NULL` for live and equality for a demo run; replace scope indexes with indexes on run identity and deadline/time.
- Update `HttpStoredArrivalAdapter` feed identity, `ConsumerSelectionService`, `ConsumerState` matching/advance methods, scope constraints, and native state writes. Preserve cursor reset when selecting a different demo run and jump-to-live-head when returning to live.
- Retain explicit `classification` only where it serves live/demo validation or AI input/response meaning. It is functional metadata, not a retained general audit feature. Replace former scope checks with classification/run checks. API DTOs can continue exposing derived scope strings where needed.
- Remove `alert_event.actor`, audit-only database comments, and general audit-writing paths. Keep `action`, confirmation facts, required event evidence, and notification/outbox logic because clients use them. Bound completed event history in Phase 7; do not recreate permanent operational auditing through event storage.

### Dependencies, tests, rollback

Affected: `AlertState*`, `DemoAlertState*`, `JpaBunchingAlertAdapter`, `AlertEvent`/`AlertEventRepository`, the unified source-state and consumer-state entities, selection services, bunching schema fixtures, `BunchingAlertIT`, `BunchingRecordingIT`, HTTP adapter tests, and dispatcher integration tests. Update live/demo controller tests and run the complete ingestion-to-recommendation demo as a consumer check.

Verify identical service/stop/direction groups in live and two demo runs remain isolated; stale/out-of-order observations do not duplicate episodes; restart retains the first candidate; confirmation emits one event and one outbox item; live reads exclude demo rows; old retry rows cannot advance a newly selected cursor. During compatibility, keep legacy state tables synchronized and keep all workers on the same lock protocol. Contraction removes the two old tables, leaving one replacement.

### Benefit

Two state tables become one, bringing the recommended core target to 24 application tables. The final bunching schema has five functional tables: `source_processing_state`, `consumer_state`, `alert_processing_state`, `alert_event`, and `diagnosis_outbox`. No general-purpose audit ledger remains there.

## Phase 7: retain topology and operational history within the configured window

### Configuration and history semantics

- Keep `ingestion.reference_dataset_version` and version-scoped topology. Historical observations must still resolve against the topology effective when they were processed; the current-only topology proposal is withdrawn.
- Use existing `OCC_INGESTION_RETENTION` as the canonical history duration to preserve environment compatibility. Propose 12h as its documented default/example, while honoring any explicitly supplied duration. Current YAML falls back to 24h and `.env.example` specifies 3h: implementation must align the inventory, YAML default, and READMEs without overwriting users' real `.env`.
- Bind the duration through the existing typed polling configuration. Reuse `OCC_INGESTION_RETENTION_INTERVAL` and `OCC_INGESTION_RETENTION_INITIAL_DELAY` for ingestion cleanup; validate positive durations. Wire equivalent retention into bunching through typed properties and document the shared-duration binding in Compose/YAML. Separate scheduling settings, if needed for bunching cleanup, must have their own documented environment names.
- Apply a clock-based cutoff to history API reads as well as cleanup. Cleanup runs periodically, so rows may physically remain until the next sweep; callers still cannot request operational history older than the configured window. Increasing the window cannot recover rows already deleted; document that behavior.
- Normalized/live history remains anchored to the observation's `fetched_at`; an observation fetched recently may legitimately predict an arrival outside that window. Do not filter by ETA as a substitute for observation retention.

### Topology retention

- Add nullable `retired_at` to `reference_dataset_version`; update `ReferenceDatasetVersion` and atomic activation in `ReferenceTopologyService` so the previous ACTIVE version records its supersession time. `imported_at` alone cannot establish when a version stopped being used.
- Always keep the ACTIVE topology, even if it was imported months ago. Also keep superseded versions whose effective interval overlaps the history window, including a version activated before the cutoff but superseded after it. Delete only a non-active version whose retirement precedes the cutoff and which has no retained operational reference.
- For pre-migration superseded versions without a known retirement time, derive an end time only where activation history is unambiguous. Otherwise start a conservative one-window grace period at migration time and check remaining references before deletion; do not infer expiry solely from import age.
- Extend `RetentionService`, reference repositories, and tests to remove expired, unreferenced topology children in dependency order: route stops, services/stops, then dataset version. Keep activation/import and pruning transactionally coordinated so a concurrent import cannot lose its active/candidate data.
- Incomplete/rejected import candidates need bounded cleanup too, using existing status/import timestamps and the same window once no import is active. Do not delete a currently executing import.
- Preserve topology needed by active operational records until those references are closed, snapshotted, or expire under a documented policy. Check arrivals, incident/context access, active demo runs, and pending diagnosis input. This is an operational dependency exception, not permanent historical retention.

### Remove permanent non-AI history and audit metadata

- Add bunching cleanup for completed source-processing state, resolved alert events, and delivered/terminal outbox rows. Remove `actor` and audit-only fields/code/comments; retain transition actions and facts required by active features.
- Do not delete source markers while a retained source can be rediscovered or a retry can replay it. Keep a processed/rejected marker until the source expires, the cursor has passed it, and no unresolved retry/active operation needs it. DEFERRED work remains until processed or terminally rejected; cleanup must not silently discard queued work.
- Keep active alert state and the minimum opening event/facts required for active-alert APIs, clear transitions, and pending dispatch. Expired resolved events can be removed after their outbox dependencies are resolved. Keep event facts in the AI input snapshot before deleting their source history.
- Apply the window to closed/completed demo history instead of retaining it indefinitely. Keep an active run's necessary topology and input until completion/closure, then age it out; update demo listing/details behavior and tests. Include traffic-incident children and vehicle assignments in deletion ordering.
- Keep poll runs/raw/normalized data as bounded functional input, not permanent request-by-request audit. Remove legacy disabled polling targets only after checking that no current selection uses them; replace old documentation claiming they are preserved for audit.
- Align `docs/CONVENTIONS.md`, ingestion/bunching/planning READMEs, `.env.example`, and technical database descriptions: operational history is configured (proposed default 12h), general auditing is removed, AI-generated response evidence has a separate long-lived policy.

### AI audit and expiring-source dependencies

- Exclude both AI invocation tables and final diagnosis/recommendation records from this cleanup. Copy the relevant request, model input/evidence, topology facts needed for interpretation, versions, and generated output into retained AI records before operational source rows expire. A UUID/hash by itself is insufficient once the referenced payload is gone.
- `diagnosis.model_invocation_record.request_id` and `root_cause_diagnosis.request_id` currently reference `diagnosis_request`. Preserve a minimal request identity anchor for retained AI records, or explicitly migrate those foreign keys/correlation fields to an independent retained AI request snapshot before deleting request rows. Do not cascade-delete AI records to satisfy operational cleanup.
- Model attempts that end in failure need self-contained input/output audit as well; they must not depend on a final diagnosis existing. Preserve corresponding input snapshots in the existing AI invocation table if no final-result parent will exist, avoiding a new general-purpose audit database.
- Pending diagnosis/recommendation work must have the input it needs when it runs. Capture a bounded immutable input snapshot in the existing request/delivery or AI context, or temporarily protect its operational inputs; preserve visibility of work that cannot proceed after expiry. Do not silently drop work at the cutoff.
- Keep `request_payload`, `mitigation_context`, `response_payload`, and diagnosis `evidence_snapshot` as the AI audit exception even if they contain expired operational facts. These snapshots do not pin the entire old ingestion topology/database forever.

### Dependencies and acceptance checks

Affected: `ReferenceDatasetVersion`, `ReferenceTopologyService`, reference/observation/incident repositories, `RetentionService`, cleanup schedules/properties, `DemoRunService`/demo queries, `RootCauseContextService`, bunching state/event/outbox repositories, planning foreign-key/reference handling, API projections, and PostgreSQL schema fixtures. Update OpenAPI if history query limits, expired-resource behavior, or demo response semantics change; retain service contracts where no outward change is necessary.

Use a fixed clock to test just inside/at/outside the configured cutoff, alternate durations, a very old ACTIVE topology, topology retired inside/outside the window, a topology refresh during cleanup, referenced superseded versions, closed demos, and cleanup reruns. Test restart/retry after cleanup and prove retained AI records remain readable with complete inputs and outputs after source observations, expired alerts, and unneeded topology are deleted. Add fresh-install and old-data upgrade tests.

### Benefit and rollback

History becomes bounded without sacrificing the topology needed to explain the retained window. Table count remains 24. Rollback can restore schema and code before contraction, but expired data is recoverable only from a backup; ordinary retention deletion is intentional. Never claim a larger duration restores already-purged history.

## Phase 8: normalize ingestion metadata, only if the joins are worthwhile

### Part A: raw metadata inherited from poll runs

- Move the authoritative `source` and `http_status` reads to `poll_run`, then remove duplicates from `raw_observation` if historical parity is proven. A raw response may need its own HTTP status if polling later becomes a multi-request batch; do not make this change if that model is required.
- Update `RawObservation`, `BusArrivalService`, `DemoReplayService`, and every query in `RawObservationRepository`. These fields currently participate in live-head, feed paging, per-ID access, and partial feed-index predicates.
- Redesign the raw feed index after removing predicates that depend on deleted columns. PostgreSQL cannot create a partial index whose predicate joins the parent table. Benchmark the joined plan rather than mechanically moving the predicate.
- Update view definitions that remain after Phase 1, `BunchingFeedService` projections, retention fixtures, and direct test inserts. Avoid one parent query per raw row.

### Part B: normalized metadata inherited from raw observations

- First remove child `poll_run_id` from `bus_arrival_observation` and `traffic_incident_observation`; derive it through `raw_observation_id`. Preserve the poll-run field in API responses.
- Only then consider child `fetched_at` and `ingested_at` removal. Both are copied into arrivals, but their time indexes currently make history and cleanup straightforward. Keep them if joined reads make code or query performance worse.
- Update `BusArrivalObservation`, `TrafficIncidentObservation`, repositories, `IngestionQueryService`, `BunchingFeedService`, `RootCauseContextService`, `DemoRunService`, `DemoReplayService`, `RetentionService`, and API mappers/projections.
- Rebuild `current_bus_arrival` with explicit projection columns rather than `observation.*`. Update current/history count queries and ordering, demo incident sorting, expiry queries, and raw-parent indexes.
- Keep deletion order safe: normalized children before raw observations, then unreferenced poll runs. Include incident child references when deciding whether a raw row can be deleted. Follow Phase 7's bounded operational retention and AI evidence exception; do not restore permanent general-audit or closed-demo retention.
- Keep explicit classification and demo/trip checks until equivalent isolation is proven; do not remove every parent-derived value in one migration.

### Validation and decision gate

Compare feed ordering, freshness, current-arrival selection, pagination/counts, history intervals, diagnosis context, and retention results against Phase 0. Ensure no N+1 queries and validate indexed join paths on representative data. Downstream bunching and diagnosis HTTP adapters should remain compatible; verify their snapshot/context calls. Upgrade tests must include both live and demo history and parent/child metadata discrepancies.

Stop after Part A or child poll-run removal if timestamp joins are not a net simplification. No table-count reduction is expected in this phase.

## Phase 9: optional replacement of spatial storage

### Changes

- Add latitude/longitude columns to `ingestion.bus_stop`; backfill from `ST_Y(location)` and `ST_X(location)` while the PostGIS extension is available. Validate range, SRID, and non-empty point data, then add constraints.
- Replace `BusStop.location`, geometry constructors and conversions in `ReferenceTopologyService`, and all test geometry fixtures. Keep topology API coordinates unchanged.
- Remove `hibernate-spatial` from ingestion's POM and unused JTS imports/dependencies once the compatibility release stops using geometry.
- Update Compose database/helper images and all PostgreSQL Testcontainers images when changing to a plain PostgreSQL runtime. Update init scripts, database documentation, and `docs/CONVENTIONS.md` if changing its declared stack.

### Critical dependency

Old ingestion V003 still creates a geometry column. Merely dropping the final geometry column does not allow fresh installation on plain PostgreSQL: the migration history still needs PostGIS. Keep PostGIS for bootstrap/upgrade, or create an explicitly versioned fresh-install baseline with a tested upgrade path and preservation of existing Flyway history. Do not edit V003 to evade this dependency. Existing data volumes also need a supported extension/image transition, not an arbitrary container-image replacement.

### Validation and rollback

Test coordinate parity, topology responses, both fresh and upgraded installations, and extension-object dependencies before removal. Backfill `location` from coordinates if rolling back before the extension is retired. This phase is optional because no spatial query requirement has been confirmed; retain spatial storage if future required behavior uses it.

## Phase 10: optional merge of the planning services

Requires a separate architecture decision; keep the four-service design if assessment criteria require it.

### Changes and dependencies

- Put diagnosis and recommendation modules in one planning process and define one ownership/migration strategy. Preserve separate domain responsibilities and validation even when runtime ownership merges.
- Replace `HttpBunchingRecommendationAdapter` with an in-process port. Keep external diagnosis/recommendation endpoints during transition so `HttpRecommendationLookupAdapter`, demo UI, and the bunching diagnosis dispatcher keep working.
- Replace the durable recommendation HTTP-delivery table/worker with a transactional local handoff or resumable planning job. Preserve restart recovery and model-call isolation; do not hold a database transaction open across model calls or remove the queue without equivalent recovery.
- Preserve both diagnosis and recommendation AI response audits during the merge. Consolidate model-call tables only after defining fields for stage (`DIAGNOSIS`/`RECOMMENDATION`), actual generated response, correlation, retries/repair attempts, validation/fallback outcomes, self-contained input evidence, versions, and provider usage. Similar names do not make the two current tables duplicate records. General audit functionality stays removed.
- Update controllers, HTTP clients, scheduling/configuration properties, POMs, Dockerfiles, Compose services/dependencies/health checks, ports, database roles, `.env.example`, README files, CI service matrix, and generated OpenAPI contracts. Provide compatibility for existing URL/env names through an explicitly documented migration.
- Migrate pending/in-flight diagnosis and delivery work and preserve request/recommendation UUIDs and idempotency hashes.

### Validation and rollback

Run equivalent end-to-end live/demo flows, provider failures, retry exhaustion, restart during each stage, lookup/replay, and concurrent submissions. Retain old services/data and endpoint routing until cutover parity is proven. Table-count savings depend on the final durable job design and should not be promised in advance.

## Recommended delivery checkpoint

Complete Phases 0-7, then reassess. Expected outcome: 24 application tables, two obsolete reserved schemas retired, two legacy database views retired, recommendation records reduced from 21 to 9 columns, four duplicated timestamps removed, one shared consumer checkpoint/selection row, bounded operational/topology history, and no general-purpose operational audit ledger. Preserve diagnosis and recommendation AI response audits and their input snapshots independently of the configured operational history window.

Implement the optional phases only where they reduce maintenance for the agreed project scope. A smaller table count by itself is not a completion criterion; the retained behavior, constraints, recovery, and explanation of existing records must still be proved.
