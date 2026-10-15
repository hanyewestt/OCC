# Phase 1 traffic lifecycle and Planning dispatch

Implementation status: typed traffic lifecycle submission and the Planning
no-action result are implemented. Unit and MVC tests passed on 15 October 2026.
Database-backed and Compose execution remain pending.

Disruption now creates a versioned `TRAFFIC_INCIDENT` Planning envelope and a
durable outbox row in the same transaction as each `OPENED`, `UPDATED`, or
`CLEARED` event and its evidence. The envelope carries the event and disruption
IDs, lifecycle action, classification/demo scope, source observation and poll
IDs, provider facts, and event time. Existing bunching envelope serialization
omits the new nullable traffic field, preserving the prior
`planning-event-v1` payload shape and fingerprint for pending bunching outbox
rows.

Planning validates the typed traffic snapshot and its scope, persists the exact
producer facts, and returns `COMPLETED` with
`policyOutcome=TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION`. This event path does not
call the bunching diagnosis/context pipeline, Gemini, or the bunching
recommendation policy. Bunching submissions keep the existing workflow.

Verification run:

```text
.\mvnw.cmd -pl services/disruption,services/planning -am -DskipITs verify
BUILD SUCCESS; Disruption 56 tests, Planning 71 tests; coverage gates passed.
```

The command compiled the integration-test sources and ran all Surefire tests.
Failsafe integration execution was explicitly skipped. The two focused suites
also passed: `TrafficIncidentSyncServiceTest` and
`JacksonPlanningEventSubmissionAdapterTest` (5 tests total), plus
`DiagnosisRequestServiceTest` and `DiagnosisRequestControllerTest` (11 tests
total).

The complete four-service root reactor was then verified with
`mvnw.cmd -pl services/ingestion,services/gateway,services/disruption,services/planning -am -DskipITs verify`:
56 Ingestion, 56 Disruption, 71 Planning and 17 Gateway Surefire tests passed.
Coverage gates passed for Ingestion, Disruption and Planning. Failsafe remained
skipped because Docker and local database/service ports were unavailable.

The following tests are present but still need execution against PostgreSQL and
the full Compose topology:

- `TrafficIncidentLifecycleIT`: verifies OPENED/UPDATED/CLEARED outbox rows,
  incomplete-feed behavior, LIVE/DEMO isolation and transaction rollback when
  evidence encoding fails.
- `DiagnosisRequestPersistenceIT`: verifies durable no-action result, retained
  traffic snapshot, idempotent replay and absence of diagnosis/recommendation
  rows.
- Full Disruption-to-Planning outbox delivery and queryable request status.

On this run, `docker version` did not return within the bounded wait, and
`http://localhost:6768/v3/api-docs` and
`http://localhost:6769/v3/api-docs` did not respond. The checked-in generated
Planning OpenAPI contract has therefore not yet been refreshed for the additive
traffic request fields and `policyOutcome` response field. Do not mark A07 or
the API-contract portion of A11 complete until the database/system tests and
generated-contract review pass.
