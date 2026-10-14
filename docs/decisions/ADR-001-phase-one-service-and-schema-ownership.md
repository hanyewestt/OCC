# ADR-001: Phase 1 service and schema ownership

- Status: Accepted
- Date: 2026-10-15
- Decision owner: User

## Context

The existing application has Ingestion, Bunching, Recommendation and Diagnosis processes. Bunching consumes Ingestion observations and delivers events to Diagnosis; Diagnosis calls Recommendation over HTTP. Traffic incident observations currently live in Ingestion without a separate detection lifecycle. The target architecture diagram and user decisions call for four applications: Ingestion, Disruption, Planning and Gateway.

## Decisions

1. Ingestion retains provider polling, raw and normalized observations, reference topology, feed health and deterministic demo generation.
2. Disruption is one JVM/schema and owns both bunching and traffic incident detection, detection state, checkpoints, retries, confirmation, and durable producer-owned delivery to Planning. Traffic observations remain Ingestion-owned facts.
3. Planning is one JVM/schema and owns diagnosis, context/evidence composition, recommendation policy, model calls, durable job state, model invocation evidence and result reads. Diagnosis and recommendation call domain services locally; the new Planning application must not call itself over HTTP.
4. The four canonical applications are Ingestion, Disruption, Planning and Gateway. Nginx remains the browser/edge entry. Gateway owns no business schema.
5. Disruption owns schema `disruption`; Planning owns schema `planning`. Move and reconcile data from `bunching`, `diagnosis`, and `recommendation` under an explicit write pause, backup, resumable import, identifier checks and before/after evidence reconciliation. HA and zero-downtime migration are out of scope for this MVP.
6. Existing applied Flyway migration files remain byte-for-byte intact. New target services have their own histories; one-time schema import is separate from Flyway and is idempotent/checkpointed. No schema is dropped as part of the code migration.
7. Cross-service references are logical IDs or immutable snapshots. No cross-schema runtime foreign keys, joins, or ORM relationships are introduced. Preserve active retries, leases, source cursors, outbox rows, model input/output evidence and independent attempts.
8. Real feeds remain `OBSERVATION_ONLY`; simulation remains explicitly scoped to a demo run. Neither inference nor a demo record provides authoritative captain/bus identity.
9. Use canonical `OCC_INGESTION_*`, `OCC_DISRUPTION_*`, `OCC_PLANNING_*`, and `OCC_GATEWAY_*` configuration. The user explicitly approved removing deprecated environment variables and compatibility aliases.
10. Phase 2 owns approval/rejection, authoritative vehicle/captain identity, authenticated captain binding, assigned instructions and new controller/captain product pages. No phase-2 tables, routes, config or scaffolding are added in phase 1.

## Consequences

- Existing service URLs and internal calls change; the contract manifest will document the mapping and all aliases will have an explicit retirement point.
- Disruption needs a newly implemented traffic lifecycle, because no existing detector was found.
- Planning consolidation must preserve separate model purposes/policies and both kinds of retained invocation evidence while avoiding duplicate persistence paths and self-HTTP.
- Code rollback uses Git revisions; database rollback requires the separately rehearsed backup/import/restore procedure.
- User-facing existing workflow behavior and its simulation safety rules are the behavior-preservation boundary.

## Verification

The pre-refactor four-service baseline is recorded in [baseline.md](../refactor%20plans/evidence/baseline.md). Required target evidence is listed in the implementation plan's phase 1 acceptance matrix and the schema mapping. This ADR does not claim the new architecture is implemented.
