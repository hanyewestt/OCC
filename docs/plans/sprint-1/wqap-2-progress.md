# WQAP-2 progress

As of 24 September 2026. Branch: `feature/wqap-2`.

## Overall status

**WQAP-2 is partially complete.** Detection, telemetry audit logging and the backend alert trigger are implemented and tested. Controller delivery/UI and deployment verification remain outstanding.

These are implementation statuses, not live Jira statuses. No Jira status was changed by this document update.

| Task | Implementation status | Remaining work |
| --- | --- | --- |
| WQAP-24 — Headway calculation | Implemented and tested; ready for review | Verify configured data/topology in the deployed environment. |
| WQAP-40 — Telemetry audit logging | Implemented; mock HTTP-to-audit flow verified; ready for review | Real-provider/deployment smoke test and audit retention policy. |
| WQAP-38 — Alert trigger | Backend implemented; local sub-five-second trigger checks pass | Production/load verification and downstream delivery integration. |
| WQAP-39 — Controller alert UI | Not implemented; deferred for now | Protected alert API/stream, reconnect handling, UI and bus-identification decision. |

## WQAP-24 — Headway calculation

**Status:** implemented and tested; ready for review.

### Implemented

- Created the independent Java 25 / Spring Boot 4.1.1 / Maven detection service. Ingestion retains its previously agreed Gradle build.
- Reads successful stored observations every second by default through ingestion's read-only views. Prefers normalized rows and otherwise maps stored raw JSON. The detector makes no DataMall calls.
- Groups arrivals by service, stop and known direction; detects gaps **strictly below the configurable threshold**, default 90 seconds. Controller threshold editing is deferred.
- Resolves raw direction only from unambiguous route topology. Rejects stale/future snapshots, unknown direction, missing-slot sequences and duplicate evidence.
- Rule `absolute-headway-v2` skips expired pairs while evaluating remaining usable adjacent pairs. For −1/+60/+120-second estimates, slots 2–3 produce a valid 60-second gap. Existing v1 records remain unchanged.
- Records each source observation once. Incomplete/transient failures receive a persisted 30-second retry deadline; malformed observations are quarantined. Failed batches cannot monopolize newer inputs.

### Remaining to-dos

- [ ] Provision route topology and verify detection against configured local/deployed observations. Synthetic topology can support a mock local run.
- [ ] Complete code review.
- [ ] Add controller threshold editing later, as requested; it is not required for the current backend increment.

### File guide

Paths below are relative to `services/bus-bunching-detection/src/main/java/com/csd/occ/bunching/`.

| Main files | Role |
| --- | --- |
| `domain/ArrivalSnapshot.java`, `domain/BunchingEvaluation.java` | Immutable input and calculation results. |
| `domain/BunchingPolicy.java` | Headway, threshold and evidence rules. |
| `domain/StoredArrivalPort.java`, `domain/BunchingConsumptionService.java` | Stored-input consumption, retries and quarantine. |
| `infrastructure/ingestion/StoredArrivalMapper.java` | Raw/normalized mapping, shared slot validation and direction resolution. |
| `infrastructure/ingestion/PostgresStoredArrivalAdapter.java` | Read-view access and persisted processing state. |
| `infrastructure/scheduling/BunchingSchedule.java` | Periodic database consumption. |

See the complete file inventory below for configuration, migrations and tests.

## WQAP-40 — End-to-end telemetry audit logging

**Status:** implemented and verified using mock HTTP responses through the actual ingestion, database and detection code; ready for review. Real-provider/deployment proof remains outstanding.

### Implemented

- Persists `POLL_STARTED` and `POLL_COMPLETED` with system actor, target, timestamps, outcome, poll correlation ID, checksum and raw-observation ID when available.
- Commits raw telemetry, terminal poll outcome, feed health and completion audit atomically. Audit-write failure rolls back the associated changes.
- Uses database claims, minute-level idempotency and leases to prevent duplicate polling and reject late completion.
- Dispatches due targets using bounded concurrency with no executor backlog. Defaults: 8 concurrent polls and 1 target dispatch per second per instance. Each target becomes due after 60 seconds; active targets are not resubmitted and overdue targets have priority.
- Independently recovers up to 100 expired polls every 10 seconds, including disabled/deleted targets and legacy null leases. Concurrent recovery is safe; older recovered failures cannot overwrite newer feed health.
- Bounds HTTP time/response size, validates response envelopes and records safe failure codes. Failures preserve last-success data; logs exclude credentials, response bodies and exception text. Failed bodies are represented by metadata/checksums, not retained verbatim.
- Retains detection evidence and rejection/retry audit history independently of source-telemetry deletion.
- Updated the legacy seeder to collect routes 190 and 972 in one pagination traversal, deduplicating shared stops and using injected configuration/time. It still creates polling targets, not route topology.

### Remaining to-dos

- [ ] Apply migrations/grants and configure the local/deployed services.
- [ ] Run a deployed smoke test with an authorized real DataMall key when available; mock end-to-end proof already exists.
- [ ] Set a separate audit retention policy and exclude audit tables from telemetry cleanup.
- [ ] Configure per-instance dispatch/concurrency limits within the provider account's total allowance. Target cadence depends on sufficient quota and capacity.
- [ ] Complete code review.

### File guide

New ingestion Java paths are relative to `services/ingestion/src/main/java/com/csd/occ/ingestion/`.

| Main files | Role |
| --- | --- |
| `domain/ArrivalPollingService.java`, `domain/ArrivalFeedPort.java`, `domain/ArrivalTelemetryPort.java` | Coordinate fetches and durable telemetry through domain contracts. |
| `infrastructure/datamall/DataMallArrivalFeedAdapter.java` | Bounded HTTP fetches, envelope validation and safe diagnostics. |
| `infrastructure/postgres/PostgresArrivalTelemetryAdapter.java` | Atomic claims/completion, audit persistence and lease recovery. |
| `infrastructure/scheduling/ArrivalPollingSchedule.java` | Bounded concurrent target dispatch. |
| `infrastructure/scheduling/PollRecoverySchedule.java` | Recovery independent of enabled targets. |
| `infrastructure/config/ArrivalPollingProperties.java`, `infrastructure/config/ArrivalSchedulingProperties.java` | Validated HTTP, freshness, concurrency and dispatch settings. |

Detector audit code lives in `domain/BunchingRecordingService.java`, the observation entity/repository and evidence encoder. See the complete inventory for all related files.

## WQAP-38 — Alert trigger

**Status:** backend trigger implemented and tested. Local sub-five-second trigger checks pass; production/load and controller-delivery latency are not verified.

### Implemented

- Opens one alert episode per service/stop/direction when bunching is detected. Repeated bunching does not create duplicate openings.
- Clears an episode on fresh normal evidence; later bunching opens a new episode. Insufficient evidence, missing groups or silence do not establish recovery. Stale/future and older/equal-time evidence cannot revise newer state.
- Commits the evaluation, alert state and retained alert event together. PostgreSQL emits an event-UUID notification on `bunching_alert` only after commit; rollback discards the notification.
- Retains group-specific event evidence in `bunching.alert_event`; `bunching.alert_state` holds the active episode and latest accepted fetch time. These are observation alerts, not operational incidents or directives.
- Uses a one-second detector polling interval by default. Existing evaluations are not retrospectively converted into alerts; new observations drive transitions.

### Remaining to-dos

- [ ] Apply detector V6 in the target environment.
- [ ] Verify the five-second target under realistic deployment load, backlog and failure conditions.
- [ ] Integrate downstream delivery with WQAP-39 when that work begins. Notifications are transient wake-ups; subscribers must reconcile retained events and deduplicate event IDs after reconnecting.
- [ ] Complete code review.

### File guide

Paths are relative to `services/bus-bunching-detection/`.

| Main files | Role |
| --- | --- |
| `src/main/java/com/csd/occ/bunching/domain/BunchingAlertPolicy.java` | Pure episode opening/clearing rules. |
| `src/main/java/com/csd/occ/bunching/domain/BunchingAlertPort.java` | Transactional alert-trigger contract. |
| `src/main/java/com/csd/occ/bunching/infrastructure/postgres/PostgresBunchingAlertAdapter.java` | Ordered group locking, durable transitions and commit-time notifications. |
| `src/main/java/com/csd/occ/bunching/domain/BunchingRecordingService.java` | Connects detection recording to the trigger in one transaction. |
| `src/main/resources/db/migration/V6__create_alert_trigger.sql` | Alert state/event tables and constraints. |
| `src/test/java/com/csd/occ/bunching/integration/BunchingAlertIT.java` | Episode, concurrency, rollback, retention and notification-latency tests. |

## WQAP-39 — Controller alert UI and delivery

**Status:** not implemented; deferred while focusing on backend work.

### Implemented

No UI or authenticated delivery endpoint has been implemented. WQAP-38 supplies retained events and PostgreSQL notifications for future integration.

### Remaining to-dos

- [ ] Build an authenticated backend alert API/stream when exposing alerts to clients; restrict access to the appropriate controller/admin roles.
- [ ] Implement subscriber reconnect/reconciliation and event deduplication; checkpoints/acknowledgements are not implemented.
- [ ] Build the controller alert UI and measure end-to-screen latency.
- [ ] Resolve stable bus identification. Arrival slots are not vehicle IDs.

### File guide

No WQAP-39 implementation files exist yet. The alert tables and trigger files listed under WQAP-38 are its backend inputs.

## Shared deployment and separate follow-up work

### To run the implemented backend locally or in deployment

- [ ] Apply the service migrations, including ingestion V005/V006 and detector V1–V6, then provision the required roles/read-view grants. Applied migrations must not be edited.
- [ ] Configure database credentials, DataMall or mock URL/key, polling limits and service startup.
- [ ] Choose `OCC_INGESTION_STALE_AFTER_SECONDS` and align `OCC_BUNCHING_STALE_AFTER`.
- [ ] Populate matching route topology. The existing seeder only creates polling targets; synthetic topology and mock arrivals can support a local backend run.
- [ ] Run a local/deployed smoke test. The local OCC database was not migrated by this work.

### Broader ingestion work not completed by these tasks

Static topology import, normalized-row production, bounded upstream retries with jitter, ingestion APIs/security and the 24-hour telemetry cleanup job remain separate work. Detection currently supports the stored-raw fallback. Audit retention must be separate from telemetry retention; no audit-deletion scheduler was introduced. Operational incident creation/clearing is also separate from alert episode handling.

## Verification record

Pre-commit verification rerun on 24 September 2026 with Docker enabled.

| Service / command | Unit tests | Integration cases | Total | Result |
| --- | ---: | ---: | ---: | --- |
| Detector — `mvn verify` | 46 | 34 | 80 | Passed; none skipped. |
| Ingestion — `gradle test bootJar` (Gradle 9.6.0) | 7 | 22 | 29 | Passed; none skipped. |
| **Combined** | **53** | **56** | **109** | **Passed; none skipped.** |

Both service JARs build. Tests use synthetic data, a local mock HTTP server at the DataMall boundary and disposable PostgreSQL 18/PostGIS containers. Production Spring wiring and migrations are covered. No real DataMall connection or deployment smoke test was performed.

| Area | Verified behavior |
| --- | --- |
| Detection | 89/90/91-second boundaries, configurable thresholds, grouping, freshness, raw/normalized mappings, missing-slot rules and usable pairs after expired estimates. |
| Processing reliability | A full batch of 100 incomplete inputs does not starve valid data; persisted retry deadlines, late commits, duplicate recording, quarantine and permissions. |
| Ingestion | HTTP errors/redirects, malformed/trailing JSON, empty responses, timeout/size limits, atomic audit rollback, leases and last-success preservation. |
| Review fixes | Seven blocked calls with an eighth target starting; bounded dispatch/concurrency; disabled/deleted-target recovery; concurrent recovery and newer-health protection; one pagination traversal for both routes. |
| Alerts | Opening/clearing/reopening, concurrent duplicates, group isolation, evidence ordering, retention, atomic failures and rollback suppressing notifications. |
| Mock end-to-end | Actual ingestion code → PostgreSQL → detector → retained audit/alert, including evidence surviving source deletion. |
| Latency | Two local checks assert under five seconds: detection-to-notification and stored-input-to-notification using the actual consumer/schedule with a one-second initial wait. |

Latency starts from a successfully stored observation, not physical bus bunching. Ingestion targets a 60-second per-stop cadence when capacity permits. Local unloaded tests do not establish production or controller-screen latency.

## Complete file inventory

All files below were added unless marked **modified**. Generated build files are excluded.

### Service setup

Paths relative to `services/bus-bunching-detection/`:

| File | Purpose |
| --- | --- |
| `pom.xml` | Defines Java 25, Spring Boot, database dependencies, unit tests and integration tests for Maven. |
| `.gitignore` | Excludes generated `target/` output from Git. |
| `Dockerfile` | Builds the service JAR and packages it in a Java runtime image. Container deployment has not been verified. |
| `README.md` | Explains the data flow, limitations, setup, configuration and test commands. |
| `src/main/resources/application.yml` | Configures database access, Flyway and consumer settings. Secrets come from environment variables. |
| `src/main/resources/db/migration/V1__create_bunching_observation.sql` | Creates the evaluation audit table, constraints and indexes. |
| `src/main/resources/db/migration/V2__support_stored_arrival_consumption.sql` | Allows an unknown topology version and creates the malformed-input rejection table. |
| `src/main/resources/db/migration/V3__schedule_source_retries.sql` | Stores retry deadlines and indexes them so failed inputs do not monopolize processing batches. |
| `src/main/resources/db/migration/V4__identify_audit_actor_and_action.sql` | Adds system actor and action to evaluation history, with defaults compatible with the previous recorder. |
| `src/main/resources/db/migration/V5__audit_source_processing_failures.sql` | Adds retained, append-only rejection/retry history with source/poll provenance and effective retry deadlines. |
| `src/main/resources/db/migration/V6__create_alert_trigger.sql` | Creates locked per-group alert state and retained opening/clearing events with evidence, provenance and uniqueness constraints. |

### Application code

Paths relative to `services/bus-bunching-detection/src/main/java/com/csd/occ/bunching/`:

| File | Purpose |
| --- | --- |
| `BunchingApplication.java` | Starts Spring Boot and discovers configuration properties. |
| `domain/ArrivalSnapshot.java` | Immutable input: source IDs, fetch time, optional topology version and arrival records. |
| `domain/BunchingPolicy.java` | Validates evidence, groups arrivals, calculates consecutive gaps and applies the supplied threshold. No database or network access. |
| `domain/BunchingEvaluation.java` | Immutable output: group states, reasons, calculated gaps, settings and source snapshot. |
| `domain/BunchingObservation.java` | JPA entity mapping the stored audit record. |
| `domain/BunchingObservationRepository.java` | Reads existing records and inserts once per source ID using PostgreSQL conflict handling. |
| `domain/BunchingRecordingService.java` | Runs the policy, records evidence and triggers alerts in one transaction; returns existing results on retries. |
| `domain/BunchingAlertPort.java` | Contract for triggering alert transitions inside the evaluation transaction. |
| `domain/BunchingAlertPolicy.java` | Pure opening/clearing rules; insufficient evidence preserves the current episode. |
| `infrastructure/postgres/PostgresBunchingAlertAdapter.java` | Locks group state in stable order, rejects out-of-order updates, records transitions and issues commit-time PostgreSQL notifications. Requires an existing transaction. |
| `domain/RecordedBunching.java` | Returns persisted metadata and whether recording was new or a duplicate. |
| `domain/BunchingEvidencePort.java` | Interface for encoding evaluation evidence without coupling domain logic to Jackson. |
| `domain/StoredArrivalPort.java` | Interface for eligible inputs, snapshots, rejections and retries, including attempt time and safe retry reason. |
| `domain/BunchingConsumptionService.java` | Processes a bounded batch, records results, quarantines invalid input and persists a 30-second cooldown for failures. |
| `domain/InvalidArrivalException.java` | Carries a safe rejection reason for malformed stored input. |
| `domain/PendingArrivalException.java` | Signals an incomplete normalization batch that must be retried. |
| `infrastructure/config/BunchingConfig.java` | Supplies the policy and UTC Clock as Spring beans. |
| `infrastructure/config/BunchingProperties.java` | Binds and validates threshold, freshness, polling interval and batch size. |
| `infrastructure/ingestion/PostgresStoredArrivalAdapter.java` | Reads ingestion views, selects eligible observations fairly, and commits rejection/retry state together with retained processing audit history. |
| `infrastructure/ingestion/StoredArrivalMapper.java` | Applies shared slot-sequence validation, maps raw/normalized input into snapshots, and resolves raw direction using imported topology. |
| `infrastructure/jackson/JacksonBunchingEvidenceAdapter.java` | Serializes complete evaluations to JSON for audit storage. |
| `infrastructure/scheduling/BunchingSchedule.java` | Runs consumption periodically and logs counts/failures without exposing source payloads. |

### Tests and fixtures

Paths relative to `services/bus-bunching-detection/src/test/`:

| File | Purpose |
| --- | --- |
| `java/com/csd/occ/bunching/domain/BunchingPolicyTest.java` | Tests threshold boundaries, grouping, sorting, freshness, missing evidence, duplicates and immutability. |
| `java/com/csd/occ/bunching/infrastructure/config/BunchingPropertiesTest.java` | Checks invalid configuration is rejected. |
| `java/com/csd/occ/bunching/infrastructure/ingestion/StoredArrivalMapperTest.java` | Tests parsing, offsets, direction ambiguity and all eight slot-presence combinations through raw and normalized mappings. |
| `java/com/csd/occ/bunching/integration/BunchingRecordingIT.java` | Tests PostgreSQL persistence, concurrent retries, rollback, constraints and evidence round-tripping. |
| `java/com/csd/occ/bunching/integration/BunchingAlertIT.java` | Tests alert episode lifecycle, concurrency, ordering, group isolation, retention, transactional failures and detection-to-notification latency. |
| `java/com/csd/occ/bunching/integration/StoredArrivalDetectionIT.java` | Tests database-to-audit flow, retries, rejection, permissions, retention, rollback and scheduled stored-input-to-alert latency. |
| `resources/db/bunching-schema.sql` | Creates the schema in the disposable persistence-test database. |
| `resources/db/ingestion-schema.sql` | Enables PostGIS and creates the ingestion schema in the disposable integration-test database. |

### WQAP-40 ingestion code

New Java paths relative to `services/ingestion/src/main/java/com/csd/occ/ingestion/`:

| File | Purpose |
| --- | --- |
| `domain/ArrivalPollTarget.java` | Validates the provider, stop code and target scope. |
| `domain/ArrivalPoll.java` | Immutable poll identity, target, start time and lease deadline. |
| `domain/ArrivalFetch.java` | Immutable response metadata and safe outcome codes; validates successful-result invariants. |
| `domain/ArrivalFeedPort.java` | Provider-independent arrival-fetch contract. |
| `domain/ArrivalTelemetryPort.java` | Target discovery, atomic claiming and completion persistence contract. |
| `domain/ArrivalPollingService.java` | Coordinates claim, fetch and persistence using injected time; propagates audit-write failures. |
| `infrastructure/config/ArrivalPollingProperties.java` | Validates URL, credential, timeout, lease, size and freshness settings; redacts credentials in its string representation. |
| `infrastructure/config/ArrivalSchedulingProperties.java` | Binds and validates per-instance worker and dispatch-rate limits. |
| `infrastructure/config/ArrivalPollingConfig.java` | Supplies the UTC Clock bean. |
| `infrastructure/datamall/DataMallArrivalFeedAdapter.java` | Calls Bus Arrival v3 with bounded response size/time, strict JSON envelope validation, checksums and safe failure codes; does not follow redirects. |
| `infrastructure/postgres/PostgresArrivalTelemetryAdapter.java` | Persists poll/audit events, claims with advisory locks and minute idempotency, recovers expired leases and atomically completes raw telemetry/feed health/audit. |
| `infrastructure/scheduling/ArrivalPollingSchedule.java` | Dispatches due targets every second with bounded concurrency/rate, no queued backlog and no duplicate in-flight work; maintains a 60-second target cadence when capacity permits. |
| `infrastructure/scheduling/PollRecoverySchedule.java` | Independently sweeps expired runs, including disabled/deleted targets, and isolates per-run recovery failures. |

Additional paths relative to `services/ingestion/`:

| File | Purpose |
| --- | --- |
| `src/main/resources/db/migration/V006__add_telemetry_audit.sql` | Adds poll leases and an independently retained audit table; no existing migration was edited. |
| `src/test/java/com/csd/occ/ingestion/integration/TelemetryAuditIT.java` | Tests mock HTTP through actual ingestion/detection/alert code, provenance, failures, concurrency, leases, retention and transactional rollback. |
| `src/test/java/com/csd/occ/ingestion/integration/IngestionWiringIT.java` | Starts the real ingestion application against PostgreSQL with network jobs disabled and verifies migration/component wiring. |
| `src/test/java/com/csd/occ/ingestion/infrastructure/config/ArrivalPollingPropertiesTest.java` | Tests invalid settings and credential redaction. |
| `src/test/java/com/csd/occ/ingestion/infrastructure/scheduling/ArrivalPollingScheduleTest.java` | Tests blocked-stop isolation, request admission limits and bounded concurrency without a queued backlog. |
| `src/test/java/com/csd/occ/ingestion/infrastructure/scheduling/InitSeederTest.java` | Mock HTTP pagination test proving both route profiles are collected in one traversal without duplicate stops. |
| `src/test/resources/db/audit-schema.sql` | Prepares schemas and PostGIS in disposable test containers. |
| `build.gradle.kts` **modified** | Keeps the previously agreed Gradle build, corrects Testcontainers coordinates and compiles actual detector sources only in test scope for cross-service verification. |
| `src/main/java/sg/edu/smu/occ/ingestion/IngestionApplication.java` **modified** | Discovers new convention-compliant packages while preserving the legacy entry point and entity scanning. The compatibility exception is documented in code. |
| `src/main/java/sg/edu/smu/occ/ingestion/service/InitSeeder.java` **modified** | Supports disabling seeding, uses injected settings/time, and collects both routes in one paginated traversal. It still seeds targets rather than topology. |
| `src/main/java/sg/edu/smu/occ/ingestion/service/BusArrivalPoller.java` **removed** | Replaced by the domain service, ports, adapters and scheduler above; avoids running two pollers. |
| `src/main/resources/application.yml` **modified** | Adds typed polling settings, explicit credential/freshness requirements, shared mock URL override and job switches. |
| `README.md` **modified** | Documents configuration, audit flow, table columns, inspection query, tests and limitations. |

### Repository integration and documentation

Paths relative to the repository root:

| File | Purpose |
| --- | --- |
| `services/ingestion/src/main/resources/db/migration/V005__expose_bunching_read_contract.sql` | Adds read views for successful stored snapshots and active route-direction mappings. |
| `infra/local/postgres/init/02_bunching_schema.sh` | Creates the optional bunching role and schema without resetting existing data. |
| `infra/local/postgres/init/03_bunching_read_grants.sh` | Grants the bunching role SELECT access to the two ingestion views, not underlying tables. |
| `infra/local/.env.example` **modified** | Adds a placeholder for the bunching database password. |
| `infra/local/compose.yaml` **modified** | Passes the optional bunching password to database initialization. |
| `infra/local/README.md` **modified** | Documents provisioning and read grants for existing database volumes. |
| `docs/plans/sprint-1/sprint-1.md` **modified** | Records WQAP-24 implementation scope and links to setup instructions. |
| `docs/plans/sprint-1/wqap-2-progress.md` | This progress summary, verification record and file guide. |

Source documents: `docs/CONVENTIONS.md`, `01-bus-data-polling.md`, and the Sprint 1 plan. `project-v2.md` was excluded.

See the [service README](../../../services/bus-bunching-detection/README.md) for setup and code details.
