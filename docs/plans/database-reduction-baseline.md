# Database reduction baseline (Phase 0)

Captured on 2026-10-07 against the local Compose environment started with:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml up -d
```

All four application services reported ready: ingestion (`6767`), bunching
(`6768`), recommendation (`6769`), and diagnosis (`6770`). PostgreSQL reported
version 18.6. This is a local, representative-development-data baseline; it is
not a production performance claim.

## Migration and provisioned-object baseline

| Schema | Applied Flyway version |
| --- | ---: |
| `ingestion` | 014 |
| `bunching` | 11 |
| `diagnosis` | 001 |
| `recommendation` | 002 |

The `operations` and `planning` schemas exist but contain no application tables
or views. Their matching `occ_operations` and `occ_planning` roles also exist.
The active service schemas and roles are `ingestion`/`occ_ingestion`,
`bunching`/`occ_bunching`, `diagnosis`/`occ_diagnosis`, and
`recommendation`/`occ_recommendation`.

The candidate views are present: `ingestion.bunching_snapshot` and
`ingestion.bunching_route_direction`. `ingestion.current_bus_arrival` is also
present and must be retained because it remains an active API-read dependency.

## Data and equality checks

| Candidate assumption | Rows checked | Mismatches | Result |
| --- | ---: | ---: | --- |
| `recommendation_id = idempotency_key` | 1 | 0 | Proven for local data |
| recommendation invocation `created_at = completed_at` | 0 | — | Blocked: no invocation rows |
| `bus_stop.imported_at = reference_dataset_version.imported_at` | 5,210 | 0 | Proven for local data |
| `bus_service.imported_at = reference_dataset_version.imported_at` | 602 | 0 | Proven for local data |
| `bus_route_stop.imported_at = reference_dataset_version.imported_at` | 26,829 | 0 | Proven for local data |

Relevant populated operational data included 6,839 raw observations, 4,708
arrival observations, 5,429 bunching observations, 118 live alert states, 34
alert events, three diagnosis requests, two diagnosis invocation records, one
root-cause diagnosis, and one recommendation record. The removable
`bunching.source_processing_audit` table is empty.

No recommendation invocation record exists in this baseline, so Phase 2 may
not remove its duplicate timestamp until a fixture or upgraded dataset proves
the equality with populated success and failure rows.

## API and query baseline

Representative responses were collected from `/api/v1/arrivals`,
`/api/v1/arrivals/history`, `/api/v1/live-bunching-alerts`, and
`/api/v1/demo-runs/active`. The current-arrivals response was empty at capture
time; arrival history returned 4,714 items, and active-alerts returned two
open-alert facts with operational evidence. No active demo run was available,
so live/demo isolation still needs a seeded-demo verification before any
contract removal.

`EXPLAIN` for `ingestion.current_bus_arrival` used a sequential scan of
`poll_run` and `bus_arrival_observation` on this local dataset. The plan is
recorded as a comparison point only; index changes require a representative
workload before being accepted. The attempted active-alert query used a stale
`resolved_at` column name and therefore did not produce a usable plan; Phase 1
must capture the plan using the current alert-event lifecycle fields.

## Audit and AI evidence classification

`bunching.source_processing_audit` is general failure audit and is a Phase 1
contraction candidate. It is empty locally, but retry/rejection state remains
functional data and must remain durable. Diagnosis and recommendation model
invocation records, final diagnosis records, recommendation records, and their
payload/evidence fields are retained AI evidence and are explicitly outside the
operational-history reduction.

## Phase-1 entry conditions

Before contracting any object, capture an administrator backup and:

1. verify no external reader uses the two ingestion bunching views;
2. seed and verify an active demo path alongside live processing;
3. populate recommendation invocation success and failure fixtures to prove
   the timestamp equality; and
4. capture the corrected active-alert and feed-paging query plans.

This preserves the required expand/transition/contract rollout: no applied
Flyway migration was altered and no schema object was removed during Phase 0.
