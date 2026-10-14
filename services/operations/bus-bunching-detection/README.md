# Bus bunching detection

This service reads ingestion snapshots over HTTP, evaluates absolute headways, and
maintains live and simulation alert episodes. Run with Java 25 and the service
Maven wrapper; the local Compose stack publishes port 6768 and uses internal port
8002. The root [.env.example](../../../.env.example) is the configuration inventory.

## Build and run

```powershell
.\mvnw.cmd verify
```

From the repository root:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml up -d --build
```

PostgreSQL owns the `bunching` schema through `occ_bunching`. Ingestion source
access uses `OCC_INGESTION_INTERNAL_URL`, with the configured connection and
response timeouts; it requires no cross-schema grants.

## Detection and recovery

The policy groups arrivals by service, stop and direction, orders their arrival
estimates and evaluates adjacent headways against `OCC_BUNCHING_THRESHOLD`.
Exactly the threshold is not bunching. Unknown direction, invalid slots, stale
or future snapshots, and missing estimates are insufficient evidence. Live
observations open an episode immediately; simulation observations require two
qualifying observations. Alert state is locked by scope and group.

Recording a successful source, changing alert state, persisting its event,
notifying listeners, and inserting diagnosis work share one transaction.
Failure rolls these changes back. A deferred source can transition atomically to
PROCESSED; only a processed source returns its original result on replay.
Terminal rejections remain excluded. Retry deadlines survive restarts.

One `consumer_state` row owns feed selection and checkpoint. Selecting another
demo resets its cursor. Returning to live jumps to the current live head.
Acknowledgements from a previous selection cannot advance the selected cursor.

## Current storage

| Table | Purpose |
| --- | --- |
| `source_processing_state` | PROCESSED, DEFERRED or REJECTED state and minimal replay result; no evaluation ledger |
| `consumer_state` | Feed selection and sequence checkpoint |
| `alert_processing_state` | Group state isolated by nullable demo-run identity |
| `alert_event` | Operational OPENED/CLEARED facts and diagnosis evidence |
| `diagnosis_outbox` | Durable delivery, leases and retry state |

V12 removes `source_processing_audit`; V13 backfills and replaces the old
evaluation, retry, rejection, selection, cursor, and live/demo alert tables.
V14 records source sequences for retention. Applied historical migrations remain
unchanged. Old binaries require the removed tables and cannot be rolled back
against the contracted schema without restoring a backup.

## Operational retention

`OCC_INGESTION_RETENTION` supplies the shared history window, default 12h.
Bunching scheduling uses the separate environment bindings documented in the
root inventory and application YAML. DEFERRED work and active live alert facts
are protected. Resolved episode events and completed outbox work expire.
Expired demo state is removed only after ingestion reports the run expired and
no pending work or current selection depends on it.

A completed source marker is eligible only after its source is absent in
ingestion, its checkpoint has passed it (or its scope is no longer selected),
and event dependencies are gone. Unknown legacy sequences are retained
conservatively until their source/checkpoint identity can be established.
An unavailable ingestion service causes cleanup to fail visibly instead of
assuming the source is gone. Increasing retention cannot recover deleted data.

`GET /internal/v1/retention-protection` exposes simulation run IDs required by
pending dispatch, deferred sources, or the selected consumer. Ingestion uses
this HTTP contract before deleting closed-run input. Diagnosis captures immutable
input before acknowledging delivery, allowing operational data to expire without
losing model evidence. AI attempt and final-result records are retained by their
own planning services and are excluded from operational cleanup.

## APIs and configuration

The public live-alert endpoint is `/api/v1/live-bunching-alerts`. Internal APIs
include consumer selection, demo alerts, canonical bunching event facts, and
retention protection. OpenAPI is generated at `/v3/api-docs`.

Poll interval, threshold, freshness, batch size, retry delay, consumer enablement,
diagnosis URL/timeout/lease/retry/batch settings and retention scheduling are
environment bindings backed by typed properties. See the root inventory rather
than introducing per-service environment files.

## Migration deployment

Stop all old bunching workers before upgrading V13 and start only workers using
the consolidated tables. Old and new versions must not overlap because their
lock targets differ. Back up a populated database and run the parity checks in
[the reduction verification document](../../../docs/plans/database-reduction-verification.md)
before contracting it. No general-purpose processing audit is retained.
