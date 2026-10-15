# Phase 1 isolated database restore rehearsal

Run date: 15 October 2026. This was a local disposable Compose rehearsal, not an AWS or production restore.

Source project: `occ-phase1-check`. Restore project: `occ-phase1-restore`. Both used `compose.yaml` with `infra/compose.test.yaml`, `.env.example`, no host-published application/database ports, and provider/workflow scheduling disabled. Both database containers used the pinned PostgreSQL 18.6/PostGIS 3.6 image. The restored project used a new named volume; the source project's existing named volume was preserved.

A custom-format archive was created with `pg_dump --format=custom` from the source Postgres container, copied through Docker to a temporary host file, and restored into the fresh project after its repeatable DB bootstrap. The exact archive was 118,858 bytes with SHA-256 `75C5A46C84D65A9A802894B85E6F9A369FF5E9C42BAC2A637E8CEF58826D9234`. The restore used `pg_restore --exit-on-error --clean --if-exists`; Compose then reran bootstrap and all three Flyway jobs before starting the four Java services.

Synthetic rows covered an Ingestion poll record, Disruption demo cursor at sequence 42, a pending Planning request, and a retained Planning model-attempt input/output. Source and restored query results matched exactly, including those values and Flyway history counts: Ingestion 17, Disruption 2, Planning 3. The restored PostgreSQL health check and all four application health checks reported healthy. No real account, provider, model, or user data was used.

After comparison, the synthetic rows were removed from both test databases and both Compose projects were stopped without `down -v`; their named volumes remain available. This proves the documented local backup/restore and startup path for the pinned image/configuration with representative pending and retained records. It does not verify off-host encryption/retention, role recovery after secret rotation, an EC2 host-loss scenario, reverse migration after target writes, production credentials/TLS, a full staging cutover, or a production recovery objective. Those remain deployment-operator gates.
