# Phase 1 traffic lifecycle and Planning dispatch

Implementation status: typed traffic lifecycle submission and the Planning
no-action result are implemented. The database-backed integration tests passed
on 15 October 2026. The generated Planning OpenAPI contract now includes the
typed traffic event fields and response property. The local Compose bunching
workflow has passed through Gateway with a real development Supabase token and
synthetic provider overrides; a traffic event has not yet been exercised
end-to-end through Compose.

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

Latest verification run:

```text
.\mvnw.cmd --batch-mode --no-transfer-progress -pl services/ingestion,services/gateway,services/disruption,services/planning verify
BUILD SUCCESS; 204 Surefire tests and 52 Failsafe integration tests passed,
with zero failures, errors, or skips. Ingestion, Disruption and Planning
coverage gates passed.
```

This run included PostgreSQL-backed lifecycle, persistence and receiver
integration tests. `TrafficIncidentLifecycleIT` passed all four tests after
fixing two test assumptions: PostgreSQL `TIMESTAMPTZ` precision is microseconds,
and live facts use `OBSERVATION_ONLY` classification while demo facts use
`SIMULATION`. No integration-test suite was skipped in this successful run.

The full bunching workflow smoke has since passed through the local Gateway
with a real Supabase ADMIN token. Provider endpoints were overridden by the
deterministic test provider, and the recommendation came from the disabled-AI
fixture path. This was not a traffic lifecycle Compose test and did not pass
through the Nginx TLS edge; the earlier synthetic-auth Nginx workflow evidence
is separate. A direct read-only request to LTA DataMall returned HTTP 200, but
the application workflow retained its provider stub.

The checked-in generated Planning OpenAPI contract was exported from the
running secured Planning service after the traffic fields were added. It
contains `TRAFFIC_INCIDENT`, `trafficSnapshot`, and the `policyOutcome`
response field. Duplicate delivery after receiver commit, restart recovery at
each documented crash point, capacity/soak, and hosted CI remain open; do not
mark A07 complete from the current unit, integration, or bunching workflow
evidence alone.
