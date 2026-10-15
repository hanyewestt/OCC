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
$env:TESTCONTAINERS_RYUK_DISABLED='true'
.\mvnw.cmd --batch-mode --no-transfer-progress -pl services/ingestion verify
.\mvnw.cmd --batch-mode --no-transfer-progress -rf :disruption verify
BUILD SUCCESS across the module retries; 213 Surefire tests and 57 Failsafe
integration tests passed, with zero failures, errors, or skips. The configured
Ingestion and Disruption JaCoCo coverage gates passed; Planning and Gateway
have no configured coverage thresholds.
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

## Lost-response retry test

On 15 October 2026, `BunchingDiagnosisDispatcherIT.retryAfterReceiverCommitAndLostResponseReusesTheSameRequest` passed in the Docker-backed Disruption integration suite. Its HTTP receiver harness records one stable request ID under the incoming idempotency key, then drops the first response after that record is made. The dispatcher leaves the outbox pending, retries the same payload/key/fingerprint, and marks it delivered on attempt two; the receiver harness contains exactly one record. The existing `DiagnosisRequestPersistenceIT` independently verifies stable database replay for the Planning receiver. Together these cover sender retry and receiver idempotency, but the receiver in the lost-response test is a test harness, not the Planning application. Full multi-container receiver-commit fault injection and restart-at-each-crash-point checks remain open.

The Java fault proxy also passed its host-only smoke on 15 October. After Docker became available, the multi-container recovery smoke passed against the isolated Compose project `occ-dispatch-20261015`: Planning committed the request before the proxy dropped HTTP 202, then Planning and Disruption restarted. The outbox remained durable and was either pending its retry or already delivered on attempt two by the time the check ran. In both cases, the proxy observed the same idempotency key, fingerprint, and body; Planning retained exactly one durable request, the recommendation remained queryable after recovery, and the simulation closed successfully. The smoke now accepts both valid retry timing states while checking the same durability and idempotency invariants.

This closes the injected receiver-commit/lost-response/restart scenario only. Restart-at-each-other-documented-crash-point coverage, live traffic-incident lifecycle through Compose, capacity/soak, hosted CI, and cloud deployment remain open; do not mark A07 complete from this one recovery scenario.
