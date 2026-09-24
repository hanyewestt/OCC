# Bus bunching detection — WQAP-24 / WQAP-38 / WQAP-40

Java 25, Spring Boot 4.1.1, Maven and PostgreSQL 18. Follows
`docs/CONVENTIONS.md`, the Sprint 1 plan and `01-bus-data-polling.md`.
`project-v2.md` is not a requirement source.

## Implemented flow

```text
Stored successful ingestion snapshot (PostgreSQL)
  → read-only ingestion views
  → normalized ArrivalSnapshot
  → deterministic BunchingPolicy
  → transactional bunching.bunching_observation audit record
```

The scheduled consumer reads the database every second by default. It makes
no DataMall/API requests and needs no API key. It prefers existing normalized rows.
When ingestion only has raw JSON (the current poller), it maps the stored response
in memory; it does not write into ingestion's normalized table.

A batch processes up to 100 observations by default. Successful recording is the
checkpoint, rather than a high-water timestamp: restart, retry and late poll commits
cannot skip unrecorded input. Each source observation commits independently. Duplicate
consumers are safe through the audit table's primary key. Incomplete normalized
batches and transient failures receive a persisted 30-second cooldown in
`bunching.source_retry`. The query skips retries until due, then orders them by
their deadline alongside fresh observations' fetch times. This prevents the oldest
failed batch from blocking every later observation. Deadlines survive restarts;
concurrent updates cannot shorten them. Failure to persist a deadline fails the
cycle visibly. Completed/rejected inputs are excluded even if retry metadata remains.
Malformed observations are recorded
in `bunching.source_rejection` with safe reason codes and do not block later input.
Failures to read the source views are logged and retried. Scheduler logs contain
counts and failed observation IDs, never payloads or database exception text.

## Detection and mapping

The policy groups arrivals by service, mapped direction and stop, sorts estimated
arrival times and computes adjacent gaps without rounding. Bunching is strictly
`gap < threshold`; the default is 90 seconds, so exactly 90 seconds is normal.

`threshold` is supplied to the policy and recording service. Runtime configuration
can change it on restart; a future controller setting can provide it without
rewriting the rule. There is no controller settings UI or endpoint yet.

Snapshots preserve source-observation and poll-run IDs, original fetch time and
available topology version. Optional fields represent unknown direction or missing
estimates. A topology version may be absent for raw input; no ID is fabricated.

For raw responses, direction is accepted only when exactly one active imported
route matches service, operator, origin, destination and monitored stop. A repeated
stop in a route or a visit other than the first is conservatively unsupported and
produces unknown direction. Missing/ambiguous topology produces
`INSUFFICIENT_EVIDENCE`; it never assigns direction 1 by default. The existing
poll-target seeder does not import topology, so deployments without imported route
reference rows will record insufficient evidence until topology is available.

`NextBus`, `NextBus2` and `NextBus3` map to arrival slots 1, 2 and 3. DataMall's
`VisitNumber` is the vehicle's visit to the stop, not the arrival slot or direction.
See the [official DataMall API guide](https://datamall.lta.gov.sg/content/dam/datamall/datasets/LTA_DataMall_API_User_Guide.pdf).
An empty trailing slot does not invalidate two available estimates. Shared validation
before either mapping rejects missing leading/interior slots rather than joining
across them. Raw-slot evidence IDs are
stable derived UUIDs scoped to the source observation; they are not vehicle IDs.

Stale/future snapshots, duplicate slots/IDs, missing estimates and
unknown direction never produce bunching. At the exact freshness deadline a
snapshot is stale. Empty successful snapshots retain empty evaluations rather than
claiming that all routes are normal. The caller supplies time; tests use a fixed Clock.

Rule `absolute-headway-v2` skips adjacent pairs containing expired estimates instead
of rejecting the entire group. A fresh snapshot at −1, +60 and +120 seconds therefore
retains the 60-second gap between slots 2 and 3. If no usable pair remains, it records
insufficient evidence (`PAST_ARRIVAL`); an estimate exactly at evaluation time remains
usable. Missing-slot and duplicate validations are unchanged. Existing v1 audit
records are preserved; new observations use v2.

## Persistence and retry semantics

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
old evidence is never silently recomputed. Source UUIDs deliberately have no
cross-schema foreign keys, so telemetry cleanup does not erase audit evidence.
No audit cleanup job is introduced. Snapshot deduplication does not replace
incident-level clearing or alert suppression across different polls.

## Deployment setup

1. Apply ingestion's migrations through V005. This adds the read-only contracts
   `ingestion.bunching_snapshot` and `ingestion.bunching_route_direction` without
   modifying existing migrations. Follow the ingestion service's migration process.
2. Set `OCC_BUNCHING_DB_PASSWORD` in the uncommitted `infra/local/.env`.
3. From the repository root, provision the bunching schema and view permissions:

```sh
docker compose -f infra/local/compose.yaml up -d postgres
docker compose -f infra/local/compose.yaml exec -T postgres bash /docker-entrypoint-initdb.d/02_bunching_schema.sh
docker compose -f infra/local/compose.yaml exec -T postgres bash /docker-entrypoint-initdb.d/03_bunching_read_grants.sh
```

The scripts preserve existing data and passwords. Do not reset the volume.
Fresh-volume initialization precedes service migrations, so rerun the grants
script after ingestion V005. The bunching role can SELECT the two views, but cannot
read underlying ingestion tables or modify ingestion data. This explicit read-view
contract is the narrow cross-service database integration; ownership remains isolated.

4. Supply these environment variables to the bunching process:

```text
OCC_BUNCHING_DB_URL=jdbc:postgresql://localhost:5432/occ
OCC_BUNCHING_DB_USER=occ_bunching
OCC_BUNCHING_DB_PASSWORD=<your local password>
OCC_BUNCHING_STALE_AFTER=<duration aligned with ingestion freshness policy>
```

For example, use `120s` for a local demo freshness limit; it is not a hard-coded
operational rule. Optional settings are `OCC_BUNCHING_THRESHOLD` (90s),
`OCC_BUNCHING_POLL_INTERVAL` (1s), `OCC_BUNCHING_BATCH_SIZE` (100), and
`OCC_BUNCHING_CONSUMER_ENABLED` (true). Invalid durations or batch sizes fail startup.

5. From this service directory, run `mvn spring-boot:run`, or `mvn package` followed
   by `java -jar target/bus-bunching-detection-0.0.1-SNAPSHOT.jar`. Flyway creates the
   service's own tables, including V3 retry scheduling, V4 evaluation actor/action,
   V5 processing-failure audit history and V6 alert state/events; Hibernate never mutates the schema.

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
or AI calls are initiated; results remain observation-only.

## Alert trigger (WQAP-38)

The recorder calls `BunchingAlertPort` only for newly recorded evaluations. Evaluation,
alert state and alert event share one transaction. Failure rolls them all back so
the existing retry path can retry the source without losing its alert.

`bunching.alert_state` holds `service_id`, `stop_code`, `direction_id`,
`last_fetched_at` and nullable `active_alert_id`. Stable row-lock ordering serializes
concurrent snapshots. A newer BUNCHING result opens an episode once; NORMAL clears
it; another BUNCHING result can open a new episode. Insufficient evidence preserves
an episode; missing groups/silence never imply recovery. Stale/future inputs do not
advance the watermark. Equal fetch times use the first committed result; older/equal
inputs cannot revise it. These alerts identify route/stop/direction, not physical buses.

`bunching.alert_event` retains `id`, `alert_id`, `action` (OPENED/CLEARED), `actor`,
`classification`, `service_id`, `stop_code`, `direction_id`, `raw_observation_id`,
`poll_run_id`, `fetched_at`, `triggered_at` and full evaluation `evidence`. It has
independent retention and no source-telemetry foreign keys. Unique episode/action
constraints prevent repeated transition events. Apply V6 before running the new
code; previous evaluations are not retrospectively turned into alerts.

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

The default one-second consumer interval reduces trigger wait time. Local tests
assert under five seconds from a stored observation through a scheduled consumer
run to a PostgreSQL notification, and from direct detection to notification.
This is not bus-event-to-screen latency: ingestion polls once per minute, and
backlogs, database contention, larger configured intervals and outages can exceed
five seconds. Deployment/load testing and WQAP-39 delivery measurement remain.
