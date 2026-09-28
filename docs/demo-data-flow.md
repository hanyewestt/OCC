# Demo data flow

## Summary of the goal run

The demo implementation added a complete, isolated path across ingestion and bus-bunching detection:

- A deterministic three-step replay generates fictional Service 190 arrivals.
- Demo rows are tagged `SIMULATION` and scoped by `demo_run_id`.
- Bunching selects either the live feed or one specific demo run; it never mixes or falls back between them.
- Demo alert state is stored separately from live alert state.
- Demo alerts include the resolved bus-stop name and road, fictional vehicle details, and the predicted-arrival headway between the bunched vehicles.
- Synthetic LTA-shaped traffic incidents are stored for future root-cause demonstrations.
- A single `demo.html` page starts the replay, polls status, lists descriptive simulation alerts, shows separate view-only live-arrivals and currently-open live-detections tables, and returns bunching to live.
- Live ingestion continues during the demo.
- Returning to live skips snapshots accumulated during the demo instead of processing stale data.
- Existing live APIs, views, history, and retention queries explicitly exclude simulation data.
- OpenAPI contracts, configuration inventory, Compose wiring, documentation, and tests were updated.

## Current backend workflow

```text
Show demo
    |
    v
Ingestion creates PREPARING demo run + fictional vehicle assignments
    |
    v
Ingestion tells bunching: select DEMO(runId)
    |
    v
Bunching resets its scoped cursor to sequence 0 for that run
    |
    v
Ingestion enables runtime demo mode and marks run RUNNING
    |
    v
Replay scheduler emits three synthetic snapshots
    |
    v
Bunching fetches only /demo-runs/{runId}/arrival-snapshots
    |
    v
Normal bunching policy evaluates snapshots and opens an alert
    |
    v
UI polls ingestion; ingestion obtains alerts from bunching and adds vehicle, stop-display, and headway details
    |
    v
Presenter presses Return to live
    |
    v
Bunching moves live cursor to current live-feed head, selects LIVE
    |
    v
Ingestion disables runtime demo mode and marks run CLOSED
```

### 1. Starting the demo

The page calls `POST /api/v1/demo-runs`.

[`DemoRunController.start()`](../services/ingestion/src/main/java/com/csd/occ/ingestion/api/DemoRunController.java) delegates to [`DemoRunService.start()`](../services/ingestion/src/main/java/com/csd/occ/ingestion/domain/DemoRunService.java), which:

1. Rejects the request if another run is active.
2. Calls `DemoReplayService.start()`.
3. Calls `BunchingControlPort.selectDemo(runId)`.
4. Sets ingestion's persisted runtime demo flag to `true`.
5. Calls `DemoReplayService.activate(runId)`, changing the run from `PREPARING` to `RUNNING`.

`PREPARING` prevents replay data from being emitted before bunching has selected the run. If selection fails, ingestion attempts to return bunching to live, marks the run `FAILED`, disables runtime demo mode, and returns `503`.

### 2. Creating the run and fictional fleet

[`DemoReplayService.start()`](../services/ingestion/src/main/java/com/csd/occ/ingestion/domain/DemoReplayService.java) requires an active reference topology, inserts `ingestion.demo_run`, and creates two `ingestion.demo_vehicle_assignment` rows: `demo-190-a` and `demo-190-b`.

Identifiers are deterministic from the configured seed, for example `DEMO-BUS-190-01` and `DEMO-CAPTAIN-190-01`. [`DemoVehicleAssignment`](../services/ingestion/src/main/java/com/csd/occ/ingestion/domain/DemoVehicleAssignment.java) currently assigns both vehicles to Service 190, direction 1, operator `DEMO-OPERATOR`, and vehicle type `DD`.

### 3. Generating each replay step

[`DemoReplaySchedule.replay()`](../services/ingestion/src/main/java/com/csd/occ/ingestion/infrastructure/config/DemoReplaySchedule.java) calls `DemoReplayService.replayNext()` at `OCC_INGESTION_DEMO_STEP_INTERVAL`, currently five seconds.

Replay emits only while `ingestion.demo_runtime_setting.enabled = true` and the active run is `RUNNING`. Each call to `DemoReplayService.emit()` writes:

- One `ingestion.poll_run`
- One `ingestion.raw_observation`
- Two `ingestion.bus_arrival_observation` rows
- From step 1 onward, one `ingestion.traffic_incident_observation`
- An incremented `demo_run.current_step`

All generated rows use `classification = SIMULATION` and the active `demo_run_id`.

### 4. How the replay creates bunching

[`DemoReplayScenario.headway()`](../services/ingestion/src/main/java/com/csd/occ/ingestion/domain/DemoReplayScenario.java) defines three steps:

| Step | Headway with 90-second threshold | Meaning |
| ---: | ---: | --- |
| 0 | 300 seconds | Clearly not bunched |
| 1 | 45 seconds | Below threshold; bunching begins |
| 2 | 45 seconds | Bunching remains present |

The leading arrival is assigned `now + 120 seconds`; the following arrival uses that ETA plus the calculated headway. After the third emission, the run becomes `REPLAY_COMPLETE`. Bunching remains in demo mode until the presenter explicitly returns to live.

### 5. Synthetic incident generation

[`DemoReplayService.emit()`](../services/ingestion/src/main/java/com/csd/occ/ingestion/domain/DemoReplayService.java) creates a traffic incident on steps 1 and 2. [`TrafficIncidentObservation`](../services/ingestion/src/main/java/com/csd/occ/ingestion/domain/TrafficIncidentObservation.java) stores its type, coordinates, message, derived source hash, fetch and ingestion timestamps, `SIMULATION` classification, and `demo_run_id`.

The raw JSON follows the LTA TrafficIncidents envelope:

```json
{
  "value": [{
    "Type": "Vehicle Breakdown",
    "Latitude": 1.380255,
    "Longitude": 103.755075,
    "Message": "DEMO: Vehicle breakdown"
  }]
}
```

These incident rows are generated and persisted, but are not yet consumed by a root-cause-analysis workflow.

### 6. Serving demo snapshots to bunching

[`DemoSnapshotController`](../services/ingestion/src/main/java/com/csd/occ/ingestion/api/DemoSnapshotController.java) exposes:

```http
GET /internal/v1/demo-runs/{runId}/arrival-snapshots
GET /internal/v1/demo-runs/{runId}/arrival-snapshots/{rawObservationId}
```

[`BunchingFeedService.demoPage()`](../services/ingestion/src/main/java/com/csd/occ/ingestion/domain/BunchingFeedService.java) builds normalized snapshots. [`RawObservationRepository`](../services/ingestion/src/main/java/com/csd/occ/ingestion/domain/RawObservationRepository.java) restricts demo reads to the requested `demo_run_id`, `source = 'DEMO'`, `classification = 'SIMULATION'`, successful poll runs, and successful HTTP responses. Separate live queries require `source = 'DATAMALL'` and `classification = 'OBSERVATION_ONLY'`.

### 7. How bunching selects the demo feed

[`HttpBunchingControlAdapter.selectDemo()`](../services/ingestion/src/main/java/com/csd/occ/ingestion/infrastructure/bunching/HttpBunchingControlAdapter.java) sends:

```http
PUT /internal/v1/consumer-selection
```

```json
{"mode":"DEMO","demoRunId":"..."}
```

[`ConsumerSelectionController.select()`](../services/operations/bus-bunching-detection/src/main/java/com/csd/occ/bunching/api/ConsumerSelectionController.java) requires a run ID for `DEMO` and forbids one for `LIVE`. [`ConsumerSelectionService.select()`](../services/operations/bus-bunching-detection/src/main/java/com/csd/occ/bunching/domain/ConsumerSelectionService.java) sets the cursor to `DEMO:{runId}`, resets its sequence to zero, and updates the singleton `bunching.consumer_selection` row.

### 8. Reading and processing demo data

[`BunchingSchedule.consumeStoredArrivals()`](../services/operations/bus-bunching-detection/src/main/java/com/csd/occ/bunching/infrastructure/scheduling/BunchingSchedule.java) runs the existing consumer loop. [`HttpStoredArrivalAdapter.selectedFeed()`](../services/operations/bus-bunching-detection/src/main/java/com/csd/occ/bunching/infrastructure/ingestion/HttpStoredArrivalAdapter.java) maps:

- `LIVE` to `/internal/v1/arrival-snapshots`
- `DEMO` to `/internal/v1/demo-runs/{runId}/arrival-snapshots`

`pendingIds()`, `snapshot()`, `complete()`, `defer()`, and `reject()` retain `classification`, `feed_scope`, and `demo_run_id`, keeping retries and rejections isolated by feed.

### 9. Bunching evaluation and alert storage

[`BunchingConsumptionService.consume()`](../services/operations/bus-bunching-detection/src/main/java/com/csd/occ/bunching/domain/BunchingConsumptionService.java) uses the normal processing path. [`BunchingRecordingService.record()`](../services/operations/bus-bunching-detection/src/main/java/com/csd/occ/bunching/domain/BunchingRecordingService.java) runs the existing `BunchingPolicy`, stores a `bunching.bunching_observation`, carries through `SIMULATION`, `DEMO:{runId}`, and `demo_run_id`, then invokes alert handling.

[`JpaBunchingAlertAdapter.trigger()`](../services/operations/bus-bunching-detection/src/main/java/com/csd/occ/bunching/infrastructure/postgres/JpaBunchingAlertAdapter.java) accepts a simulation evaluation only when the selected mode is `DEMO` for the same run, and accepts a live evaluation only in `LIVE` mode. Simulation alerts use `bunching.demo_alert_state`, leaving live `bunching.alert_state` untouched. `triggerDemo()` stores the transition, demo run, service, stop, direction, evidence, and leading/following observation IDs.

### 10. Returning alerts to the UI

[`DemoAlertController.alerts()`](../services/operations/bus-bunching-detection/src/main/java/com/csd/occ/bunching/api/DemoAlertController.java) returns only `SIMULATION` alerts for the requested run. [`DemoRunService.details()`](../services/ingestion/src/main/java/com/csd/occ/ingestion/domain/DemoRunService.java):

1. Fetches those alerts from bunching.
2. Uses their leading and following observation IDs.
3. Joins each simulated arrival to `demo_vehicle_assignment`.
4. Resolves the stop description and road name from the active reference topology.
5. Calculates the absolute gap between the two simulated predicted-arrival times as `headwaySeconds`.
6. Adds registration, operator, vehicle type, and captain code.
7. Returns the assembled response to `demo.html`.

### 11. Demo page presentation and live-arrival viewing

[`demo.html`](../services/ingestion/src/main/resources/demo.html) is served by ingestion and uses same-origin browser requests only. For an opened simulation alert, it displays **BUNCHING DETECTED (SIMULATION)** rather than the internal `SIMULATION.OPENED` transition notation. Each alert shows the service, direction, resolved stop name/road/code, detection time, predicted-arrival headway, and fictional leading/following vehicle identities.

The page also calls the existing public `GET /api/v1/arrivals?size=50` endpoint every five seconds and resolves each distinct stop through `GET /api/v1/bus-stops/{stopCode}`. It renders these results in a separate **Live bus arrivals** table for observation only. It also polls ingestion's same-origin `GET /api/v1/live-bunching-alerts` proxy every five seconds whenever no simulation run is active. The proxy calls bunching's public `GET /api/v1/live-bunching-alerts`, which joins live `alert_state.active_alert_id` to its immutable opening `alert_event`; therefore it returns only currently open `OBSERVATION_ONLY` episodes, not cleared history or simulation data. The live-detections table is intentionally hidden during a simulation run, preserving the demo's feed isolation. This browser polling neither creates simulation rows nor sends any data to bunching, so it cannot affect the active demo run or live detection.

## Switching from demo back to live

The page calls `POST /api/v1/demo-runs/{runId}/close`. `DemoRunService.close()` invokes `returnToLive()`:

1. Ingestion calls `bunching.selectLive()`.
2. Bunching calls ingestion's live-feed-head endpoint.
3. Bunching sets its live cursor to the current head sequence.
4. Bunching changes `consumer_selection` to `LIVE`.
5. Ingestion sets `demo_runtime_setting.enabled = false`.
6. Ingestion marks the demo run `CLOSED`.

The live head comes from [`BunchingSnapshotController.liveHead()`](../services/ingestion/src/main/java/com/csd/occ/ingestion/api/BunchingSnapshotController.java) through [`HttpLiveFeedHeadAdapter.currentLiveSequence()`](../services/operations/bus-bunching-detection/src/main/java/com/csd/occ/bunching/infrastructure/ingestion/HttpLiveFeedHeadAdapter.java). This skips live snapshots accumulated during the demo and prevents a burst of stale alerts.

The runtime endpoint `PUT /api/v1/demo-mode` with `{"enabled":false}` performs the same switch without restarting either application through `DemoRunService.setMode()`.

## Schema changes

### Ingestion schema

[`V011__add_demo_replay_and_traffic_incidents.sql`](../services/ingestion/src/main/resources/db/migration/V011__add_demo_replay_and_traffic_incidents.sql) creates:

| Table | Purpose |
| --- | --- |
| `ingestion.demo_run` | Run identity, scenario/version/seed, step, status, timestamps, and failure reason |
| `ingestion.demo_vehicle_assignment` | Run-scoped fictional trip, registration, operator, vehicle type, and captain |
| `ingestion.traffic_incident_observation` | Normalized TrafficIncidents observations for eventual live use and demo data |

It extends existing tables:

| Table | Added fields |
| --- | --- |
| `ingestion.poll_run` | `classification`, `demo_run_id` |
| `ingestion.raw_observation` | `demo_run_id`; classification is constrained against it |
| `ingestion.bus_arrival_observation` | `classification`, `demo_run_id`, `trip_id` |

[`V012__add_runtime_demo_mode.sql`](../services/ingestion/src/main/resources/db/migration/V012__add_runtime_demo_mode.sql) creates the singleton `ingestion.demo_runtime_setting`. [`V013__isolate_demo_feed_and_prepare_runs.sql`](../services/ingestion/src/main/resources/db/migration/V013__isolate_demo_feed_and_prepare_runs.sql) adds `PREPARING`, includes it in the one-active-run constraint, and rebuilds `ingestion.current_bus_arrival` to include only `OBSERVATION_ONLY` rows.

### Bunching schema

[`V8__add_demo_consumer_selection.sql`](../services/operations/bus-bunching-detection/src/main/resources/db/migration/V8__add_demo_consumer_selection.sql) creates:

| Table | Purpose |
| --- | --- |
| `bunching.consumer_selection` | Durable singleton `LIVE`/`DEMO` selection with optimistic locking |
| `bunching.demo_alert_state` | Run-scoped alert episode state, separate from live alerts |

[`V9__scope_demo_alert_events.sql`](../services/operations/bus-bunching-detection/src/main/resources/db/migration/V9__scope_demo_alert_events.sql) adds `demo_run_id`, `feed_scope`, consistency constraints, and a demo-run index to `bunching.alert_event`.

[`V10__scope_processing_state_by_feed.sql`](../services/operations/bus-bunching-detection/src/main/resources/db/migration/V10__scope_processing_state_by_feed.sql) scopes `ingestion_feed_cursor`, `source_retry`, `source_rejection`, `source_processing_audit`, `bunching_observation`, `alert_event`, and `demo_alert_state` by feed. It also adds leading and following observation IDs to demo alert state and events.

## How existing live behavior is protected

- New live rows default to `OBSERVATION_ONLY`.
- [`BusArrivalService.poll()`](../services/ingestion/src/main/java/com/csd/occ/ingestion/domain/BusArrivalService.java) continues polling DataMall during demos.
- Live poll, raw, and normalized rows are explicitly marked `OBSERVATION_ONLY`.
- Current-arrival and history queries filter out simulation rows.
- The public poll-run query returns only live runs.
- [`RetentionService.purgeExpiredObservations()`](../services/ingestion/src/main/java/com/csd/occ/ingestion/domain/RetentionService.java) deletes only expired live observations, preserving demo evidence.
- Bunching feed queries are separate for live and demo.
- Live `alert_state` is not reused for simulations.
- Database constraints reject inconsistent combinations such as `SIMULATION` without a `demo_run_id`.

## Configuration and deployment boundary

[`DemoProperties`](../services/ingestion/src/main/java/com/csd/occ/ingestion/infrastructure/config/DemoProperties.java) binds and validates demo settings.

Runtime mode is stored in `ingestion.demo_runtime_setting` and `bunching.consumer_selection`. It starts disabled, and can switch between demo and live while both applications remain running. The demo page and APIs are always available; the scheduler checks the persisted state and does nothing until demo mode is enabled.

Settings are documented in [`.env.example`](../.env.example) and wired through [`services/ingestion/src/main/resources/application.yml`](../services/ingestion/src/main/resources/application.yml) and [`infra/local/compose.yaml`](../infra/local/compose.yaml).
