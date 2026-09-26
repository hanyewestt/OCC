# WQAP-2 progress

As of 24 September 2026. Branch: `feature/wqap-2`.

## Overall status

**WQAP-2 is partially complete.** Detection, telemetry audit logging and the
backend alert trigger are implemented and tested. Controller delivery/UI and
deployment verification remain outstanding.


| Task | Implementation status | Remaining work |
| --- | --- | --- |
| WQAP-24 — Headway calculation | Implemented and tested; ready for review | Verify configured data/topology in the deployed environment. |
| WQAP-40 — Telemetry audit logging | Implemented; mock HTTP-to-audit flow verified; ready for review | Real-provider/deployment smoke test and audit retention policy. |
| WQAP-38 — Alert trigger | Backend implemented; local sub-five-second trigger checks pass | Production/load verification and downstream delivery integration. |
| WQAP-39 — Controller alert UI | Not implemented; deferred for now | Protected alert API/stream, reconnect handling, UI and bus-identification decision. |

## WQAP-24 — Headway calculation

**Status:** implemented and tested; ready for review.

The detector service, stored-input integration, headway policy and durable
evaluation audit are implemented. Detailed data flow, detection rules,
persistence behavior, schemas and file responsibilities are maintained in the
[detector README](../../../services/bus-bunching-detection/README.md).

### Remaining to-dos

- [ ] Provision route topology and verify detection against configured local/deployed observations. Synthetic topology can support a mock local run.
- [ ] Complete code review.
- [ ] Add controller threshold editing later, as requested; it is not required for the current backend increment.

## WQAP-40 — End-to-end telemetry audit logging

**Status:** implemented and verified using mock HTTP responses through the actual
ingestion, database and detection code; ready for review. Real-provider/deployment
proof remains outstanding.

The ingestion poller, transactional telemetry audit flow, bounded scheduling and
lease recovery are implemented. Detailed ingestion behavior and configuration are
documented in the [ingestion README](../../../services/ingestion/README.md).

### Remaining to-dos

- [ ] Apply migrations/grants and configure the local/deployed services.
- [ ] Run a deployed smoke test with an authorized real DataMall key when available; mock end-to-end proof already exists.
- [ ] Set a separate audit retention policy and exclude audit tables from telemetry cleanup.
- [ ] Configure per-instance dispatch/concurrency limits within the provider account's total allowance. Target cadence depends on sufficient quota and capacity.
- [ ] Complete code review.

## WQAP-38 — Alert trigger

**Status:** backend trigger implemented and tested. Local sub-five-second trigger
checks pass; production/load and controller-delivery latency are not verified.

The backend alert episode trigger is implemented on top of the detector evaluation
audit. The detector README documents the alert state/event model and PostgreSQL
notification behavior.

### Remaining to-dos

- [ ] Apply detector V6 in the target environment.
- [ ] Verify the five-second target under realistic deployment load, backlog and failure conditions.
- [ ] Integrate downstream delivery with WQAP-39 when that work begins. Notifications are transient wake-ups; subscribers must reconcile retained events and deduplicate event IDs after reconnecting.
- [ ] Complete code review.

## WQAP-39 — Controller alert UI and delivery

**Status:** not implemented; deferred while focusing on backend work.

No UI or authenticated delivery endpoint has been implemented. WQAP-38 supplies
retained events and PostgreSQL notifications for future integration.

### Remaining to-dos

- [ ] Build an authenticated backend alert API/stream when exposing alerts to clients; restrict access to the appropriate controller/admin roles.
- [ ] Implement subscriber reconnect/reconciliation and event deduplication; checkpoints/acknowledgements are not implemented.
- [ ] Build the controller alert UI and measure end-to-screen latency.
- [ ] Resolve stable bus identification. Arrival slots are not vehicle IDs.

## Shared deployment and separate follow-up work

### To run the implemented backend locally or in deployment

- [ ] Apply the service migrations, including ingestion V005–V007 and detector V1–V6, then provision the required roles/read-view grants. Applied migrations must not be edited.
- [ ] Configure database credentials, DataMall or mock URL/key, polling limits and service startup.
- [ ] Choose `OCC_INGESTION_STALE_AFTER_SECONDS` and align `OCC_BUNCHING_STALE_AFTER`.
- [ ] Populate matching route topology. The existing seeder only creates polling targets; synthetic topology and mock arrivals can support a local backend run.
- [ ] Run a local/deployed smoke test. The local OCC database was not migrated by this work.

### Broader ingestion work not completed by these tasks

Static topology import, normalized-row production, bounded upstream retries with
jitter, ingestion APIs/security and the 24-hour telemetry cleanup job remain
separate work. Detection currently supports the stored-raw fallback. Audit
retention must be separate from telemetry retention; no audit-deletion scheduler
was introduced. Operational incident creation/clearing is also separate from
alert episode handling.

## Verification record

Pre-commit verification rerun on 24 September 2026 with Docker enabled.

| Service / command | Unit tests | Integration cases | Total | Result |
| --- | ---: | ---: | ---: | --- |
| Detector — `mvn verify` | 46 | 34 | 80 | Passed; none skipped. |
| Ingestion — `gradle test bootJar` (Gradle 9.6.0) | 7 | 22 | 29 | Passed; none skipped. |
| **Combined** | **53** | **56** | **109** | **Passed; none skipped.** |

Both service JARs build. Tests use synthetic data, a local mock HTTP server at the
DataMall boundary and disposable PostgreSQL 18/PostGIS containers. Production
Spring wiring and migrations are covered. No real DataMall connection or
deployment smoke test was performed.

| Area | Verified behavior |
| --- | --- |
| Detection | 89/90/91-second boundaries, configurable thresholds, grouping, freshness, raw/normalized mappings, missing-slot rules and usable pairs after expired estimates. |
| Processing reliability | A full batch of 100 incomplete inputs does not starve valid data; persisted retry deadlines, late commits, duplicate recording, quarantine and permissions. |
| Ingestion | HTTP errors/redirects, malformed/trailing JSON, empty responses, timeout/size limits, atomic audit rollback, leases and last-success preservation. |
| Review fixes | Seven blocked calls with an eighth target starting; bounded dispatch/concurrency; disabled/deleted-target recovery; concurrent recovery and newer-health protection; one pagination traversal for both routes. |
| Alerts | Opening/clearing/reopening, concurrent duplicates, group isolation, evidence ordering, retention, atomic failures and rollback suppressing notifications. |
| Mock end-to-end | Actual ingestion code → PostgreSQL → detector → retained audit/alert, including evidence surviving source deletion. |
| Latency | Two local checks assert under five seconds: detection-to-notification and stored-input-to-notification using the actual consumer/schedule with a one-second initial wait. |

Latency starts from a successfully stored observation, not physical bus bunching.
Ingestion targets a 60-second per-stop cadence when capacity permits. Local unloaded
tests do not establish production or controller-screen latency.

## Documentation ownership

The service READMEs are the source of detailed implementation and file-level
documentation. This document records WQAP status, remaining work, deployment
checklists, verification evidence and scope decisions.

- [Bus-bunching detection README](../../../services/bus-bunching-detection/README.md)
- [Ingestion README](../../../services/ingestion/README.md)
