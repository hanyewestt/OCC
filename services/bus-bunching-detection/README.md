# Bus bunching detection — WQAP-24 / WQAP-38 / WQAP-40

Java 25, Spring Boot 4.1.1, Maven and PostgreSQL 18. Follows
`docs/CONVENTIONS.md`, the Sprint 1 plan and `01-bus-data-polling.md`.
`project-v2.md` is not a requirement source.

## Contents

- [Scope and data flow](#scope-and-data-flow)
- [Database schemas](#database-schemas)
- [Detection rules](#detection-rules)
   - [Mapping and evidence rules](#mapping-and-evidence-rules)
   - [Processing, retries and idempotency](#processing-retries-and-idempotency)
- [Persistence and audit evidence](#persistence-and-audit-evidence)
- [Alert trigger (WQAP-38)](#alert-trigger-wqap-38)
- [File guide](#file-guide)
   - [Application and domain](#application-and-domain)
   - [Infrastructure](#infrastructure)
   - [Database migrations](#database-migrations)
   - [Tests and fixtures](#tests-and-fixtures)
- [Deployment setup](#deployment-setup)
- [Verification and remaining scope](#verification-and-remaining-scope)

## Scope and data flow

This service consumes normalized ingestion snapshots over the ingestion service's
internal HTTP API. It does not call DataMall, require an API key, or connect to the
ingestion database.

```text
DataMall poll
   → ingestion internal snapshot API
   → ArrivalSnapshot
   → BunchingPolicy
   → bunching.bunching_observation
   → alert state/event transition when appropriate
```

## Database schemas

The detector uses two PostgreSQL schemas with separate ownership:

| Schema | Owner | Purpose |
| --- | --- | --- |
| `ingestion` | Ingestion service | Stores provider poll runs, raw DataMall responses, normalized arrival rows, feed health, polling targets and route topology. It exposes snapshots through an internal API. |
| `bunching` | Bunching detector | Stores immutable evaluation evidence, retry/rejection history and alert state/events. The detector writes here. |

The ingestion service owns its source tables. Its internal snapshot API is the only
cross-service read contract. The detector has no ingestion database credentials.

The detector schema contains:

- `bunching.bunching_observation`: one immutable evaluation per source observation,
   including the threshold, rule version, result count and full JSON evidence.
- `bunching.source_retry`: the next time an incomplete or transiently failed source
   should be attempted.
- `bunching.source_rejection`: the quarantine record for malformed or unsupported
   source input.
- `bunching.source_processing_audit`: append-only rejection and deferral history,
   retained independently from source telemetry.
- `bunching.alert_state`: the current open/closed episode for each service, stop and
   direction.
- `bunching.alert_event`: durable `OPENED` and `CLEARED` transitions, including the
   evidence and source provenance used for the transition.

Deleting old ingestion telemetry does not delete detector evidence: the detector
audit tables deliberately do not use foreign keys to the short-lived ingestion data.

## Detection rules

1. Ingestion polls DataMall and stores a successful raw response and, when available,
   normalized rows in the `ingestion` schema.
2. `BunchingSchedule` wakes the consumer. `BunchingConsumptionService` selects an
   eligible source observation, skipping completed, rejected and not-yet-due retries.
3. `HttpStoredArrivalAdapter` reads normalized snapshots from ingestion and tracks a
   durable local feed cursor plus retry/rejection state.
4. `BunchingPolicy` validates freshness, arrival slots, estimates and direction. It
   groups arrivals by service, stop and direction, sorts their estimated times, and
   calculates adjacent headways:

   ```text
   headway = later estimated arrival - earlier estimated arrival
   bunching = headway < configured threshold
   ```

   The default threshold is 90 seconds; exactly 90 seconds is not bunching. Unknown
   direction, ambiguous topology, stale/future snapshots, missing estimates and
   invalid slot sequences produce insufficient evidence instead of a false alert.
5. `BunchingRecordingService` persists the evaluation and evidence once per source
   observation. A new evaluation then calls the alert port in the same transaction.
6. `JpaBunchingAlertAdapter` locks group state in stable order, opens one episode
   for new bunching, clears it only on fresh normal evidence, and records the durable
   event. PostgreSQL sends only the event UUID as a commit-time notification.

The detector therefore reports a successful bunching evaluation in
`bunching.bunching_observation` when `bunched_group_count > 0`. An active alert is
visible in `bunching.alert_state` when `active_alert_id` is not null.

### Mapping and evidence rules

The policy groups arrivals by service, mapped direction and stop, sorts estimated
arrival times and computes adjacent gaps without rounding. `threshold` is supplied
to the policy and recording service. Runtime configuration can change it on restart;
a future controller setting can provide it without rewriting the rule. There is no
controller settings UI or endpoint yet.

Snapshots preserve source-observation and poll-run IDs, original fetch time and
available topology version. Optional fields represent unknown direction or missing
estimates. A topology version may be absent for raw input; no ID is fabricated.

For raw responses, direction is accepted only when exactly one active imported route
matches service, operator, origin, destination and monitored stop. A repeated stop in
a route or a visit other than the first is conservatively unsupported and produces
unknown direction. Missing or ambiguous topology produces `INSUFFICIENT_EVIDENCE`;
it never assigns direction 1 by default. The existing poll-target seeder does not
import topology, so deployments without imported route reference rows record
insufficient evidence until topology is available.

`NextBus`, `NextBus2` and `NextBus3` map to arrival slots 1, 2 and 3. DataMall's
`VisitNumber` is the vehicle's visit to the stop, not the arrival slot or direction.
See the [official DataMall API guide](https://datamall.lta.gov.sg/content/dam/datamall/datasets/LTA_DataMall_API_User_Guide.pdf).
An empty trailing slot does not invalidate two available estimates. Shared validation
before either mapping rejects missing leading or interior slots rather than joining
across them. Raw-slot evidence IDs are stable derived UUIDs scoped to the source
observation; they are not vehicle IDs.

Stale/future snapshots, duplicate slots/IDs, missing estimates and unknown direction
never produce bunching. At the exact freshness deadline a snapshot is stale. Empty
successful snapshots retain empty evaluations rather than claiming that all routes
are normal. The caller supplies time; tests use a fixed Clock.

Rule `absolute-headway-v2` skips adjacent pairs containing expired estimates instead
of rejecting the entire group. A fresh snapshot at -1, +60 and +120 seconds therefore
retains the 60-second gap between slots 2 and 3. If no usable pair remains, it records
insufficient evidence (`PAST_ARRIVAL`); an estimate exactly at evaluation time remains
usable. Missing-slot and duplicate validations are unchanged. Existing v1 audit
records are preserved; new observations use v2.

### Processing, retries and idempotency

A batch processes up to 100 observations by default. Successful recording is the
checkpoint, rather than a high-water timestamp: restart, retry and late poll commits
cannot skip unrecorded input. Each source observation commits independently. Duplicate
consumers are safe through the audit table's primary key.

Incomplete normalized batches and transient failures receive a persisted 30-second
cooldown in `bunching.source_retry`. The query skips retries until due, then orders
them by their deadline alongside fresh observations' fetch times. This prevents the
oldest failed batch from blocking every later observation. Deadlines survive restarts;
concurrent updates cannot shorten them. Failure to persist a deadline fails the cycle
visibly. Completed/rejected inputs are excluded even if retry metadata remains.

Malformed observations are recorded in `bunching.source_rejection` with safe reason
codes and do not block later input. Failures to read the source views are logged and
retried. Scheduler logs contain counts and failed observation IDs, never payloads or
database exception text.

## Persistence and audit evidence

`bunching.bunching_observation` retains one immutable evaluation per raw source ID:

| Column | Meaning |
| --- | --- |
| `raw_observation_id` | Primary key and idempotency key |
| `poll_run_id`, `reference_version_id` | Source provenance; reference may be unknown |
| `fetched_at`, `evaluated_at` | Original fetch time and evaluation time |
| `actor`, `action` | Automatic system identity and `HEADWAY_EVALUATED` action |
| `threshold_seconds`, `stale_after_seconds` | Exact durations used |
| `rule_version` | Deterministic detection-rule version |
| `classification` | Always `OBSERVATION_ONLY` |
| `bunched_group_count` | Number of bunched service/direction/stop groups |
| `evidence` | JSONB containing the input snapshot, headways, results and reasons |

`ON CONFLICT DO NOTHING` handles concurrent retries. A retry returns the original
record, even after configuration changes. New thresholds apply to new observations;
old evidence is never silently recomputed. Snapshot deduplication does not replace
incident-level clearing or alert suppression across different polls. No audit cleanup
job is introduced.

## Alert trigger (WQAP-38)

The recorder calls `BunchingAlertPort` only for newly recorded evaluations. Evaluation,
alert state and alert event share one transaction. Failure rolls them all back so the
existing retry path can retry the source without losing its alert.

`bunching.alert_state` holds `service_id`, `stop_code`, `direction_id`,
`last_fetched_at` and nullable `active_alert_id`. Stable row-lock ordering serializes
concurrent snapshots. A newer BUNCHING result opens an episode once; NORMAL clears it;
another BUNCHING result can open a new episode. Insufficient evidence preserves an
episode; missing groups/silence never imply recovery. Stale/future inputs do not
advance the watermark. Equal fetch times use the first committed result; older/equal
inputs cannot revise it. These alerts identify route/stop/direction, not physical buses.

`bunching.alert_event` retains `id`, `alert_id`, `action` (OPENED/CLEARED), `actor`,
`classification`, `service_id`, `stop_code`, `direction_id`, `raw_observation_id`,
`poll_run_id`, `fetched_at`, `triggered_at` and full evaluation `evidence`. It has
independent retention and no source-telemetry foreign keys. Unique episode/action
constraints prevent repeated transition events. Apply V6 before running the new code;
previous evaluations are not retrospectively turned into alerts.

After commit, PostgreSQL publishes the event UUID on `bunching_alert`. A trusted
backend subscriber can `LISTEN bunching_alert` and fetch the matching event:

```sql
LISTEN bunching_alert;
SELECT * FROM bunching.alert_event ORDER BY triggered_at, id;
```

Notifications are wake-ups, not durable delivery. A reconnecting subscriber must
reconcile retained events and deduplicate event IDs; timestamp-only cursors can miss
late commits. No frontend database access, subscriber/acknowledgement service or
authenticated alert HTTP endpoint is added in this increment. Those belong to the
controller integration; no credentials or payloads are placed in notifications.

The default one-second consumer interval reduces trigger wait time. Local tests assert
under five seconds from a stored observation through a scheduled consumer run to a
PostgreSQL notification, and from direct detection to notification. This is not
bus-event-to-screen latency: ingestion polls once per minute, and backlogs, database
contention, larger configured intervals and outages can exceed five seconds.

## File guide

### Application and domain

| File | Responsibility |
| --- | --- |
| `src/main/java/com/csd/occ/bunching/BunchingApplication.java` | Spring Boot entry point and configuration-properties scanning. |
| `domain/ArrivalSnapshot.java` | Immutable detector input containing source provenance, fetch time, topology and arrivals. |
| `domain/BunchingPolicy.java` | Pure validation, grouping, headway calculation and bunching classification. |
| `domain/BunchingEvaluation.java` | Immutable result containing group outcomes, gaps, reasons and settings. |
| `domain/BunchingRecordingService.java` | Coordinates evaluation persistence and alert triggering in one transaction. |
| `domain/BunchingObservation.java` | JPA mapping for the immutable evaluation audit row. |
| `domain/BunchingObservationRepository.java` | Idempotent insert and retrieval of evaluation records. |
| `domain/RecordedBunching.java` | Return value identifying the persisted evaluation and whether it was new. |
| `domain/BunchingAlertPolicy.java` | Pure rules for opening, clearing and preserving alert episodes. |
| `domain/BunchingAlertPort.java` | Domain contract for transactional alert transitions. |
| `domain/BunchingEvidencePort.java` | Domain contract for encoding evaluation evidence. |
| `domain/StoredArrivalPort.java` | Domain contract for reading, rejecting and retrying stored inputs. |
| `domain/BunchingConsumptionService.java` | Processes bounded input batches and handles retry, rejection and quarantine outcomes. |
| `domain/InvalidArrivalException.java` | Safe failure used for malformed or unsupported source data. |
| `domain/PendingArrivalException.java` | Signals incomplete normalization that should be retried. |

### Infrastructure

| File | Responsibility |
| --- | --- |
| `infrastructure/ingestion/HttpStoredArrivalAdapter.java` | Reads the ingestion HTTP feed and persists local retry, rejection, cursor and processing-audit state through JPA repositories. |
| `infrastructure/postgres/JpaBunchingAlertAdapter.java` | Persists alert state/events and emits commit-time PostgreSQL notifications through JPA repositories. |
| `infrastructure/jackson/JacksonBunchingEvidenceAdapter.java` | Serializes complete evaluation evidence to JSONB. |
| `infrastructure/scheduling/BunchingSchedule.java` | Runs the stored-input consumer on its configured interval. |
| `infrastructure/config/BunchingProperties.java` | Validates threshold, freshness, interval, batch size and consumer settings. |
| `infrastructure/config/BunchingConfig.java` | Creates the configured policy and UTC clock beans. |

### Database migrations

| File | Creates or changes |
| --- | --- |
| `db/migration/V1__create_bunching_observation.sql` | Evaluation evidence table and indexes. |
| `db/migration/V2__support_stored_arrival_consumption.sql` | Allows topology version to be unknown for raw stored input. |
| `db/migration/V3__schedule_source_retries.sql` | Retry queue for incomplete or transiently failed inputs. |
| `db/migration/V4__identify_audit_actor_and_action.sql` | Actor and action columns on evaluation audit rows. |
| `db/migration/V5__audit_source_processing_failures.sql` | Append-only rejection and deferral audit history. |
| `db/migration/V6__create_alert_trigger.sql` | Current alert state and durable alert transition events. |
| `db/migration/V7__add_ingestion_feed_cursor.sql` | Durable cursor for the ingestion HTTP feed. |

### Tests and fixtures

| File | Coverage |
| --- | --- |
| `src/test/java/com/csd/occ/bunching/domain/BunchingPolicyTest.java` | Pure headway, grouping, freshness, slot and evidence rules. |
| `src/test/java/com/csd/occ/bunching/infrastructure/config/BunchingPropertiesTest.java` | Invalid configuration and boundary validation. |
| `src/test/java/com/csd/occ/bunching/integration/BunchingRecordingIT.java` | Evaluation persistence, idempotency, rollback and evidence retention. |
| `src/test/java/com/csd/occ/bunching/integration/BunchingAlertIT.java` | Alert lifecycle, concurrency, retention, rollback and notification latency. |
| `src/test/resources/db/bunching-schema.sql` | Disposable detector schema for integration tests. |

## Deployment setup

1. Apply ingestion's migrations through V008. This adds the durable internal feed
   sequence without modifying existing migrations. Follow the ingestion service's migration process.
2. Set `OCC_BUNCHING_DB_PASSWORD` in the uncommitted repository-root `.env`.
3. From the repository root, provision the bunching schema:

```sh
docker compose -f infra/local/compose.yaml up -d postgres
docker compose -f infra/local/compose.yaml exec -T postgres bash /docker-entrypoint-initdb.d/02_bunching_schema.sh
```

The scripts preserve existing data and passwords. Do not reset the volume.
The bunching role owns only its own schema. The ingestion service owns and protects
its data behind the internal HTTP contract.

4. Supply these environment variables to the bunching process:

```text
OCC_BUNCHING_DB_URL=jdbc:postgresql://localhost:5432/occ
OCC_BUNCHING_DB_USER=occ_bunching
OCC_BUNCHING_DB_PASSWORD=<your local password>
OCC_INGESTION_PUBLIC_URL=http://localhost:<OCC_INGESTION_PORT>
OCC_BUNCHING_STALE_AFTER=<duration aligned with ingestion freshness policy>
```

For local Maven or IDE runs, activate the `local` profile. It imports the
repository-root `.env`; deployment must instead provide the variables through its
environment or secret store. Compose supplies `OCC_INGESTION_INTERNAL_URL` as
`http://ingestion:<OCC_INGESTION_INTERNAL_PORT>`, which takes precedence over the
public URL so service traffic remains private.

For example, use `120s` for a local demo freshness limit; it is not a hard-coded
operational rule. Optional settings are `OCC_BUNCHING_THRESHOLD` (90s),
`OCC_BUNCHING_POLL_INTERVAL` (1s), `OCC_BUNCHING_BATCH_SIZE` (100), and
`OCC_BUNCHING_CONSUMER_ENABLED` (true). Invalid durations or batch sizes fail startup.

5. From this service directory, run `mvn spring-boot:run -Dspring-boot.run.profiles=local`, or `mvn package` followed
   by `java -jar target/bus-bunching-detection-0.0.1-SNAPSHOT.jar`. Flyway creates the
   service's own tables, including V3 retry scheduling, V4 evaluation actor/action,
   V5 processing-failure audit history, V6 alert state/events and V7 feed cursor;
   Hibernate never mutates the schema.

For inspection, connect as the appropriate database owner and query:

```sql
SELECT raw_observation_id, evaluated_at, threshold_seconds, bunched_group_count,
       evidence -> 'groups' AS groups
FROM bunching.bunching_observation
ORDER BY evaluated_at DESC;
SELECT * FROM bunching.source_rejection ORDER BY rejected_at DESC;
SELECT * FROM bunching.source_processing_audit ORDER BY occurred_at DESC;
```

A malformed immutable source is quarantined. After correcting the upstream problem,
create a new successful observation; do not rewrite retained audit history. Missing
source telemetry due to the 24-hour retention window cannot be recovered by this
consumer; monitor prolonged outages and provision sufficient processing throughput.

## Verification and remaining scope

Run `mvn verify` with Java 25 and Docker. Unit tests require no Spring context;
Failsafe integration tests use disposable PostgreSQL 18/PostGIS, real ingestion
migrations, the actual setup/grant scripts and bunching migrations. They verify
stored raw and normalized inputs through the policy into audit records, boundaries,
configuration changes, stale/ambiguous input, malformed input isolation, late commits,
partial-normalization retry, duplicates, rollback, permissions and evidence retention.
Regression tests include a full batch of 100 incomplete inputs ahead of valid
observations, retry boundaries and every slot-presence combination through both mappings.
Docker-less runs skip integration tests and must not be reported as database proof.
All telemetry is synthetic: these tests do not claim real DataMall connectivity.

WQAP-40 adds explicit evaluation actor/action and transactionally retained rejection/retry
history. Each failed processing attempt records a safe reason and available source/poll
provenance; deferred attempts also record their effective retry deadline. Audit records
survive telemetry deletion and must be excluded from its 24-hour cleanup.

WQAP-24's backend calculation and stored-input integration are implemented. WQAP-40's
mock HTTP-to-audit proof runs in the [ingestion integration tests](../ingestion/README.md)
using the actual poller and detector code. A real-provider/deployment smoke run remains.
Production alert delivery/latency, operational incident clearing, WQAP-39 UI and
stable vehicle identity remain outside this increment. No operational directives
or AI calls are initiated; results remain observation-only. Deployment/load testing
and WQAP-39 delivery measurement remain.
