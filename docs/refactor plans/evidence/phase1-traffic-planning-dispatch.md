# Phase 1 traffic lifecycle and Planning dispatch

Implementation status: typed traffic lifecycle submission and the Planning
no-action result are implemented. The database-backed integration tests passed
on 15 October 2026. Full Compose system delivery remains pending because Docker
Desktop's engine stopped during the Compose smoke run.

Disruption creates a versioned `TRAFFIC_INCIDENT` Planning envelope and a
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
.\mvnw.cmd -pl services/ingestion,services/gateway,services/disruption,services/planning verify
BUILD SUCCESS; 200 Surefire tests and 52 Failsafe integration tests passed.
Ingestion, Disruption and Planning coverage gates passed.
```

This run included PostgreSQL-backed lifecycle, persistence and receiver
integration tests. `TrafficIncidentLifecycleIT` passed all four tests after
fixing two test assumptions: PostgreSQL `TIMESTAMPTZ` precision is microseconds,
and live facts use `OBSERVATION_ONLY` classification while demo facts use
`SIMULATION`. No integration-test suite was skipped in this successful run.

The Compose smoke run used an isolated project and synthetic ES256 auth/JWKS
fixtures, not real Supabase credentials. All four application/migration images
built; database bootstrap and all schema migrations completed, and Ingestion
became healthy. Docker Desktop then returned Engine errors and stopped before
the complete edge-to-service workflow could be verified. After restart, the
Desktop UI reported a Windows `dockerInference` socket initialization error,
and the Docker Engine pipe was unavailable. The user is resetting containers
and volumes; rebuild and rerun the full Compose workflow after the Engine is
available.

The checked-in generated Planning OpenAPI contract has not yet been refreshed
for the additive traffic request fields and `policyOutcome` response field.
Do not mark A07 or the API-contract portion of A11 complete until a full Compose
delivery run and generated-contract review pass.
